package com.chatapp.server;

import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.Peer;
import com.chatapp.protocol.Message;
import com.chatapp.protocol.Message.HistoryRequest;
import com.chatapp.protocol.Message.HistorySnapshot;
import com.chatapp.protocol.Message.SenderRole;
import com.chatapp.protocol.Message.StateSync;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages this server's outgoing TCP connection to the current leader.
 *
 * <p>When this server is <em>not</em> the leader, it dials the leader's TCP port, sends a {@link
 * HistoryRequest}, and then processes incoming {@link HistorySnapshot} and {@link StateSync}
 * messages to keep the local {@link ChatHistory} in sync.
 *
 * <p>Reconnects automatically when the leader changes (call {@link #reconnect(int)} from the
 * election callback) or when the connection drops.
 */
public final class ReplicaConnector implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ReplicaConnector.class);

  private final int myId;
  private final GroupView groupView;
  private final AtomicInteger currentLeaderId;
  private final ServerConfig config;
  private final ChatHistory history;

  private volatile ClientSession leaderSession;
  private volatile boolean running = true;

  public ReplicaConnector(
      ServerConfig config,
      GroupView groupView,
      AtomicInteger currentLeaderId,
      ChatHistory history) {
    this.myId = config.serverId();
    this.config = config;
    this.groupView = groupView;
    this.currentLeaderId = currentLeaderId;
    this.history = history;
  }

  /**
   * Called when the current leader changes (election result). If this server is not the new leader,
   * it disconnects from the old leader and reconnects to the new one.
   */
  public void reconnect(int newLeaderId) {
    if (newLeaderId == myId) {
      disconnect();
      return;
    }
    disconnect();
    Thread.ofVirtual().name("replica-connector-" + myId).start(() -> connectToLeader(newLeaderId));
  }

  private void connectToLeader(int leaderId) {
    Peer leader =
        groupView.snapshot().stream().filter(p -> p.id() == leaderId).findFirst().orElse(null);
    if (leader == null) {
      log.warn("event=leader_not_found myId={} leaderId={}", myId, leaderId);
      return;
    }

    try {
      Socket socket = new Socket(leader.host(), leader.port());
      ClientSession session = new ClientSession(socket);
      this.leaderSession = session;

      log.info(
          "event=connected_to_leader myId={} leaderId={} host={} port={}",
          myId,
          leaderId,
          leader.host(),
          leader.port());

      // Request the full history on connect.
      session.send(new HistoryRequest(myId, SenderRole.SERVER, System.currentTimeMillis()));

      receiveLoop(session, leaderId);
    } catch (IOException e) {
      log.warn(
          "event=leader_connect_failed myId={} leaderId={} error={}", myId, leaderId, e.toString());
    }
  }

  private void receiveLoop(ClientSession session, int leaderId) {
    try {
      while (running && session.isOpen()) {
        Message msg = session.receive();
        if (msg == null) break;

        switch (msg) {
          case HistorySnapshot snap -> {
            history.resetFromSnapshot(snap.messages());
            log.info(
                "event=history_snapshot_applied myId={} entries={}", myId, snap.messages().size());
          }
          case StateSync sync -> {
            history.applyDelta(sync.messages());
            log.debug("event=state_sync_applied myId={} delta={}", myId, sync.messages().size());
          }
          default ->
              log.debug(
                  "event=replica_rx_ignored myId={} type={}", myId, msg.getClass().getSimpleName());
        }
      }
    } catch (Exception e) {
      if (running) {
        log.warn(
            "event=leader_connection_lost myId={} leaderId={} error={}",
            myId,
            leaderId,
            e.toString());
      }
    } finally {
      session.close();
    }
  }

  private void disconnect() {
    ClientSession s = leaderSession;
    if (s != null) {
      s.close();
      leaderSession = null;
    }
  }

  @Override
  public void close() {
    running = false;
    disconnect();
  }
}
