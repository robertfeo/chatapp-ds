package com.chatapp.election;

import com.chatapp.config.Config;
import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.UdpSender;
import com.chatapp.protocol.Message;
import com.chatapp.protocol.Message.Answer;
import com.chatapp.protocol.Message.ElectionInquiry;
import com.chatapp.protocol.Message.IAmLeader;
import com.chatapp.protocol.Message.SenderRole;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leader election by the <b>Bully algorithm</b> (Garcia-Molina, 1982), the crash-fault-tolerant,
 * highest-id-wins scheme from the course (lecture "12 elections"). Not LCR and not a ring: the
 * servers are a small fully-connected group that already knows each other's ids via discovery.
 *
 * <p>Three message types, matching the lecture:
 *
 * <ul>
 *   <li>{@link ElectionInquiry} ("election") - a server calls an election by announcing its
 *       candidacy.
 *   <li>{@link Answer} ("answer"/"I am alive") - a higher-id server replies to suppress a lower
 *       candidate and take the election over.
 *   <li>{@link IAmLeader} ("coordinator") - the winner announces itself as the new leader.
 * </ul>
 *
 * <p>Round for a server P:
 *
 * <ol>
 *   <li>P sends an {@code ElectionInquiry}. If no higher-id server answers within {@link
 *       Config#ELECTION_TIMEOUT_S} seconds, P wins and broadcasts {@code IAmLeader}.
 *   <li>If a higher-id server answered, P stands down and waits for that server's {@code
 *       IAmLeader}; if none arrives in time, P restarts the election.
 *   <li>If P receives an inquiry from a <em>lower</em> id, it answers and starts its own election.
 *   <li>If P receives an {@code IAmLeader} from a <em>lower</em> id, it bullies it out by starting
 *       a new election.
 * </ol>
 *
 * <p>Messages go out by UDP broadcast on the discovery port: on a real LAN the broadcast reaches
 * every server on the subnet, and on localhost it reaches the sibling processes that share the
 * discovery port via SO_REUSEPORT (a unicast there would reach only one of them). Receivers act on
 * the sender's id relative to their own, which is exactly the "send to higher ids only" semantics.
 */
public final class ElectionService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ElectionService.class);

  private final int myId;
  private final int discoveryPort;
  private final String broadcastAddr;
  private final GroupView groupView;
  private final UdpSender udpSend;
  private final AtomicInteger currentLeaderId;
  private final IntConsumer onLeaderChanged;

  private final AtomicBoolean electionRunning = new AtomicBoolean(false);

  /** Set by message handlers during a running round; read by the timeout tasks. */
  private volatile boolean higherAnswered;

  private volatile boolean coordinatorSeen;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "election-scheduler"));

  public ElectionService(
      ServerConfig config, GroupView groupView, UdpSender udpSend, AtomicInteger currentLeaderId) {
    this(config, groupView, udpSend, currentLeaderId, id -> {});
  }

  public ElectionService(
      ServerConfig config,
      GroupView groupView,
      UdpSender udpSend,
      AtomicInteger currentLeaderId,
      IntConsumer onLeaderChanged) {
    this.myId = config.serverId();
    this.discoveryPort = config.discoveryPort();
    this.broadcastAddr = config.broadcastAddr();
    this.groupView = groupView;
    this.udpSend = udpSend;
    this.currentLeaderId = currentLeaderId;
    this.onLeaderChanged = onLeaderChanged;
  }

  /**
   * Schedules a one-shot cold-start election. A freshly booted cluster has no prior leader to die,
   * so the heartbeat-triggered path never fires on its own. After the group view has had {@link
   * Config#ELECTION_BOOTSTRAP_DELAY_S} seconds to converge, if no leader is known yet, run one
   * election so the highest live id becomes the initial leader.
   */
  public void scheduleBootstrap() {
    scheduler.schedule(
        () -> {
          if (currentLeaderId.get() == -1) {
            log.info("event=bootstrap_election myId={}", myId);
            startElection();
          } else {
            log.debug("event=bootstrap_skipped reason=leader_known myId={}", myId);
          }
        },
        Config.ELECTION_BOOTSTRAP_DELAY_S,
        TimeUnit.SECONDS);
  }

  /**
   * Starts a Bully election round if none is running. Safe to call from multiple threads; only one
   * round runs at a time.
   */
  public void startElection() {
    if (!electionRunning.compareAndSet(false, true)) {
      log.debug("event=election_skipped reason=already_running myId={}", myId);
      return;
    }
    higherAnswered = false;
    coordinatorSeen = false;
    log.info("event=election_started myId={} group={}", myId, groupView.ids());

    // Bully assumes complete knowledge of peer ids: if no live peer outranks us, we win outright
    // without waiting for answers that cannot come.
    if (Election.pickLeader(groupView.ids()) == myId) {
      declareCoordinator();
      electionRunning.set(false);
      return;
    }

    broadcast(new ElectionInquiry(myId, SenderRole.SERVER, System.currentTimeMillis()));
    scheduler.schedule(this::onAnswerDeadline, Config.ELECTION_TIMEOUT_S, TimeUnit.SECONDS);
  }

  /** A peer is calling an election. A lower-id candidate is suppressed and we take over. */
  public void onElectionInquiry(ElectionInquiry msg) {
    if (msg.senderId() < myId) {
      broadcast(new Answer(myId, SenderRole.SERVER, System.currentTimeMillis()));
      log.info("event=answer_sent myId={} to={}", myId, msg.senderId());
      startElection();
    }
    // A higher-id inquiry is that peer's job to resolve; nothing to do.
  }

  /** A higher-id server answered our inquiry, so it will take over and we stand down. */
  public void onAnswer(Answer msg) {
    if (msg.senderId() > myId) {
      higherAnswered = true;
      log.info("event=answer_received myId={} from={}", myId, msg.senderId());
    }
  }

  /**
   * Coordinator (leader) announcement. Accept a leader whose id is at least ours; bully out a
   * leader with a lower id by starting a fresh election.
   */
  public void onCoordinator(IAmLeader msg) {
    if (msg.leaderId() >= myId) {
      coordinatorSeen = true;
      currentLeaderId.set(msg.leaderId());
      electionRunning.set(false);
      onLeaderChanged.accept(msg.leaderId());
      log.info("event=leader_accepted myId={} leaderId={}", myId, msg.leaderId());
    } else {
      log.info("event=leader_rejected myId={} announcedLeaderId={}", myId, msg.leaderId());
      startElection();
    }
  }

  private void onAnswerDeadline() {
    if (!electionRunning.get()) {
      return; // a coordinator already resolved the round
    }
    if (higherAnswered) {
      // A higher peer is taking over; wait for its coordinator message before giving up.
      scheduler.schedule(this::onCoordinatorDeadline, Config.ELECTION_TIMEOUT_S, TimeUnit.SECONDS);
    } else {
      // No higher peer answered: we win.
      declareCoordinator();
      electionRunning.set(false);
    }
  }

  private void onCoordinatorDeadline() {
    if (!electionRunning.get() || coordinatorSeen) {
      electionRunning.set(false);
      return;
    }
    // A higher peer answered but never announced itself (it may have crashed); try again.
    log.info("event=election_restart myId={} reason=no_coordinator", myId);
    electionRunning.set(false);
    startElection();
  }

  private void declareCoordinator() {
    currentLeaderId.set(myId);
    onLeaderChanged.accept(myId);
    broadcast(new IAmLeader(myId, SenderRole.SERVER, System.currentTimeMillis(), myId));
    log.info("event=leader_elected myId={}", myId);
  }

  private void broadcast(Message message) {
    try {
      udpSend.send(message, new InetSocketAddress(broadcastAddr, discoveryPort));
    } catch (Exception e) {
      log.warn(
          "event=send_failed myId={} type={} error={}",
          myId,
          message.getClass().getSimpleName(),
          e.toString());
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
