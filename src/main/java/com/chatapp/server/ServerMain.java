package com.chatapp.server;

import com.chatapp.config.Config;
import com.chatapp.config.ConfigException;
import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.DiscoveryService;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.Peer;
import com.chatapp.election.ElectionService;
import com.chatapp.heartbeat.HeartbeatService;
import com.chatapp.protocol.Message;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Entry point for a server process: {@code java -jar chatapp.jar server}.
 *
 * <p>Loads and validates configuration, starts UDP discovery, heartbeat, and leader election, then
 * parks until Ctrl+C / SIGTERM.
 */
public final class ServerMain {

  private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

  private ServerMain() {}

  public static void main(String[] args) {
    ServerConfig config;
    try {
      config = ServerConfig.fromEnv();
    } catch (ConfigException e) {
      System.err.println("server startup failed: " + e.getMessage());
      System.exit(2);
      return;
    }

    MDC.put("serverId", Integer.toString(config.serverId()));
    log.info(
        "event=startup serverId={} listen={}:{} discoveryPort={} broadcast={}"
            + " heartbeatInterval={}s peerDeadTimeout={}s",
        config.serverId(),
        config.listenHost(),
        config.listenPort(),
        config.discoveryPort(),
        config.broadcastAddr(),
        Config.HEARTBEAT_INTERVAL_S,
        Config.PEER_DEAD_TIMEOUT_S);

    GroupView groupView = new GroupView();
    groupView.upsert(new Peer(config.serverId(), config.listenHost(), config.listenPort()));

    // -1 means no leader is known yet; a valid server ID is always > 0.
    AtomicInteger currentLeaderId = new AtomicInteger(-1);

    // AtomicReferences break the circular dependency: DiscoveryService dispatches to
    // HeartbeatService and ElectionService, which in turn send via DiscoveryService.send().
    // The refs are set before discovery.start(), so no message can arrive before they are ready.
    AtomicReference<ElectionService> electionRef = new AtomicReference<>();
    AtomicReference<HeartbeatService> heartbeatRef = new AtomicReference<>();

    DiscoveryService discovery =
        new DiscoveryService(
            config,
            groupView,
            () -> {
              int id = currentLeaderId.get();
              return id == -1 ? null : id;
            },
            msg -> {
              switch (msg) {
                case Message.Heartbeat hb -> heartbeatRef.get().onHeartbeatReceived(hb);
                case Message.ElectionVote v -> electionRef.get().onVoteReceived(v);
                case Message.IAmLeader leader -> electionRef.get().onLeaderAnnounced(leader);
                default -> {}
              }
            });

    ElectionService election =
        new ElectionService(config, groupView, discovery::send, currentLeaderId);

    HeartbeatService heartbeat =
        new HeartbeatService(
            config,
            groupView,
            discovery::send,
            deadPeerId -> {
              if (deadPeerId == currentLeaderId.get()) {
                election.triggerElection();
              }
            });

    electionRef.set(election);
    heartbeatRef.set(heartbeat);

    try {
      discovery.start();
    } catch (SocketException e) {
      System.err.println("server startup failed: cannot bind discovery port: " + e.getMessage());
      discovery.close();
      System.exit(3);
      return;
    }

    heartbeat.start();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  heartbeat.close();
                  election.close();
                  discovery.close();
                  log.info("event=shutdown serverId={}", config.serverId());
                }));

    log.info("event=ready serverId={}", config.serverId());

    park();
  }

  private static void park() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
