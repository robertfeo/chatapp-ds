package com.chatapp.heartbeat;

import com.chatapp.config.Config;
import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.Peer;
import com.chatapp.discovery.UdpSender;
import com.chatapp.protocol.Message.Heartbeat;
import com.chatapp.protocol.Message.SenderRole;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a {@link Heartbeat} UDP unicast to every known peer every {@link
 * Config#HEARTBEAT_INTERVAL_S} seconds and tracks when each peer was last heard from. A peer whose
 * last heartbeat is older than {@link Config#PEER_DEAD_TIMEOUT_S} seconds is declared dead and
 * removed from the {@link GroupView}.
 */
public final class HeartbeatService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

  private final int myId;
  private final int discoveryPort;
  private final GroupView groupView;
  private final UdpSender udpSend;
  private final IntConsumer onPeerDead;

  /** Epoch-ms of the last heartbeat received per peer id. */
  private final Map<Integer, Long> lastSeen = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "heartbeat-scheduler"));

  public HeartbeatService(
      ServerConfig config, GroupView groupView, UdpSender udpSend, IntConsumer onPeerDead) {
    this.myId = config.serverId();
    this.discoveryPort = config.discoveryPort();
    this.groupView = groupView;
    this.udpSend = udpSend;
    this.onPeerDead = onPeerDead;
  }

  /** Begin periodic heartbeat sending and liveness checks. */
  public void start() {
    scheduler.scheduleAtFixedRate(
        this::tick, Config.HEARTBEAT_INTERVAL_S, Config.HEARTBEAT_INTERVAL_S, TimeUnit.SECONDS);
  }

  /** Called by the UDP dispatcher whenever a {@link Heartbeat} arrives from a peer. */
  public void onHeartbeatReceived(Heartbeat hb) {
    lastSeen.put(hb.senderId(), System.currentTimeMillis());
    log.debug("event=heartbeat_received myId={} from={}", myId, hb.senderId());
  }

  private void tick() {
    sendHeartbeats();
    checkLiveness();
  }

  private void sendHeartbeats() {
    Heartbeat hb = new Heartbeat(myId, SenderRole.SERVER, System.currentTimeMillis());
    for (Peer peer : groupView.snapshot()) {
      if (peer.id() == myId) continue;
      // Initialize lastSeen so newly discovered peers get a grace period.
      lastSeen.putIfAbsent(peer.id(), System.currentTimeMillis());
      try {
        udpSend.send(hb, new InetSocketAddress(peer.host(), discoveryPort));
      } catch (Exception e) {
        log.warn(
            "event=heartbeat_send_failed myId={} target={} error={}",
            myId,
            peer.id(),
            e.toString());
      }
    }
  }

  private void checkLiveness() {
    long now = System.currentTimeMillis();
    long deadThresholdMs = (long) Config.PEER_DEAD_TIMEOUT_S * 1000;

    for (Map.Entry<Integer, Long> entry : lastSeen.entrySet()) {
      int peerId = entry.getKey();
      if (!groupView.contains(peerId)) {
        lastSeen.remove(peerId);
        continue;
      }
      if (now - entry.getValue() > deadThresholdMs) {
        log.warn("event=peer_dead myId={} deadPeer={}", myId, peerId);
        groupView.remove(peerId);
        lastSeen.remove(peerId);
        onPeerDead.accept(peerId);
      }
    }
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }
}
