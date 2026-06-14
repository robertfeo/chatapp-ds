package com.chatapp.election;

import com.chatapp.config.Config;
import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.UdpSender;
import com.chatapp.protocol.Message.ElectionVote;
import com.chatapp.protocol.Message.IAmLeader;
import com.chatapp.protocol.Message.SenderRole;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the highest-ID-wins election lifecycle.
 *
 * <p>When triggered: broadcasts an {@link ElectionVote} to all known peers, waits {@link
 * Config#ELECTION_TIMEOUT_S} seconds for votes to arrive, then calls {@link Election#pickLeader} on
 * all collected candidate IDs. If this server wins, it broadcasts {@link IAmLeader}.
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
  private final Set<Integer> collectedCandidateIds = ConcurrentHashMap.newKeySet();

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
   * so the death-triggered path in {@link #triggerElection()} never fires on its own. After the
   * group view has had {@link Config#ELECTION_BOOTSTRAP_DELAY_S} seconds to converge, if no leader
   * is known yet, run one election so the highest live id becomes the initial leader.
   */
  public void scheduleBootstrap() {
    scheduler.schedule(
        () -> {
          if (currentLeaderId.get() == -1) {
            log.info("event=bootstrap_election myId={}", myId);
            triggerElection();
          } else {
            log.debug(
                "event=bootstrap_skipped reason=leader_known myId={} leaderId={}",
                myId,
                currentLeaderId.get());
          }
        },
        Config.ELECTION_BOOTSTRAP_DELAY_S,
        TimeUnit.SECONDS);
  }

  /**
   * Starts an election round if none is running. Safe to call from multiple threads; only one round
   * runs at a time.
   */
  public void triggerElection() {
    if (!electionRunning.compareAndSet(false, true)) {
      log.debug("event=election_skipped reason=already_running myId={}", myId);
      return;
    }
    log.info("event=election_started myId={} group={}", myId, groupView.ids());
    collectedCandidateIds.clear();
    collectedCandidateIds.add(myId); // self-vote

    broadcastVote();
    scheduler.schedule(this::concludeElection, Config.ELECTION_TIMEOUT_S, TimeUnit.SECONDS);
  }

  /** Called by the UDP dispatcher when an {@link ElectionVote} arrives. */
  public void onVoteReceived(ElectionVote vote) {
    if (electionRunning.get()) {
      collectedCandidateIds.add(vote.candidateId());
      log.debug(
          "event=vote_received myId={} from={} candidate={}",
          myId,
          vote.senderId(),
          vote.candidateId());
    }
  }

  /**
   * Called when an {@link IAmLeader} arrives from another server. Accepts the winner and stops any
   * running election.
   *
   * <p>Highest-id-wins is enforced here: an announcement of a leader with a <em>lower</em> id than
   * our own is stale or the result of a vote that never reached the announcer, so we refuse it and
   * assert ourselves with a fresh election instead of silently accepting a worse leader. This keeps
   * the cluster self-correcting if elections race or a vote is lost.
   */
  public void onLeaderAnnounced(IAmLeader msg) {
    if (msg.leaderId() < myId) {
      log.info("event=leader_rejected myId={} announcedLeaderId={}", myId, msg.leaderId());
      triggerElection();
      return;
    }
    currentLeaderId.set(msg.leaderId());
    electionRunning.set(false);
    onLeaderChanged.accept(msg.leaderId());
    log.info("event=leader_accepted myId={} leaderId={}", myId, msg.leaderId());
  }

  private void broadcastVote() {
    // Broadcast (not per-peer unicast): on localhost the peers share one discovery port via
    // SO_REUSEPORT, where unicast is delivered to a single socket; only a broadcast reaches them
    // all. On a real LAN the broadcast reaches every server on the subnet just the same.
    ElectionVote vote = new ElectionVote(myId, SenderRole.SERVER, System.currentTimeMillis(), myId);
    try {
      udpSend.send(vote, new InetSocketAddress(broadcastAddr, discoveryPort));
    } catch (Exception e) {
      log.warn("event=vote_send_failed myId={} error={}", myId, e.toString());
    }
  }

  private void concludeElection() {
    try {
      int winnerId = Election.pickLeader(collectedCandidateIds);
      log.info(
          "event=election_concluded myId={} winnerId={} candidates={}",
          myId,
          winnerId,
          collectedCandidateIds);

      if (winnerId == myId) {
        currentLeaderId.set(myId);
        onLeaderChanged.accept(myId);
        broadcastLeadership();
      }
      // Non-winners wait for the IAmLeader broadcast and handle it in onLeaderAnnounced().
    } catch (Exception e) {
      log.error("event=election_error myId={} error={}", myId, e.toString());
    } finally {
      electionRunning.set(false);
    }
  }

  private void broadcastLeadership() {
    // Broadcast for the same reason as the vote: reach every peer, including localhost siblings
    // sharing the discovery port via SO_REUSEPORT.
    IAmLeader announcement =
        new IAmLeader(myId, SenderRole.SERVER, System.currentTimeMillis(), myId);
    try {
      udpSend.send(announcement, new InetSocketAddress(broadcastAddr, discoveryPort));
    } catch (Exception e) {
      log.warn("event=leader_announce_failed myId={} error={}", myId, e.toString());
    }
    log.info("event=leader_elected myId={}", myId);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
