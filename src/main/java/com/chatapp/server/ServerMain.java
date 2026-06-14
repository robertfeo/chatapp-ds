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
import com.chatapp.protocol.Message.Chat;
import com.chatapp.protocol.Message.ChatEntry;
import com.chatapp.protocol.Message.HistoryRequest;
import com.chatapp.protocol.Message.HistorySnapshot;
import com.chatapp.protocol.Message.SenderRole;
import com.chatapp.protocol.Message.StateSync;
import com.chatapp.server.dashboard.DashboardEvents;
import com.chatapp.server.dashboard.DashboardLogRouting;
import com.chatapp.server.dashboard.ServerDashboard;
import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Entry point for a server process: {@code java -jar chatapp.jar server}.
 *
 * <p>Loads and validates configuration, starts UDP discovery, heartbeat, leader election, a TCP
 * server for client and replica connections, and replica state sync, then parks until Ctrl+C /
 * SIGTERM.
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

    int myId = config.serverId();
    GroupView groupView = new GroupView();
    groupView.upsert(new Peer(myId, config.listenHost(), config.listenPort()));

    AtomicInteger currentLeaderId = new AtomicInteger(-1);
    ChatHistory chatHistory = new ChatHistory();

    // The leader's connection counts, learned from its heartbeats, so a replica's dashboard can
    // show the cluster's client/replica counts (its own are always zero: clients connect only to
    // the leader).
    AtomicInteger leaderClientCount = new AtomicInteger(0);
    AtomicInteger leaderReplicaCount = new AtomicInteger(0);

    // AtomicReferences break circular construction dependencies.
    AtomicReference<ElectionService> electionRef = new AtomicReference<>();
    AtomicReference<HeartbeatService> heartbeatRef = new AtomicReference<>();
    AtomicReference<TcpServer> tcpServerRef = new AtomicReference<>();
    AtomicReference<ReplicaConnector> replicaConnRef = new AtomicReference<>();
    AtomicReference<ServerDashboard> dashboardRef = new AtomicReference<>();

    // UDP layer: discovery handles non-discovery datagrams via extraHandler.
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
                case Message.Heartbeat hb -> {
                  heartbeatRef.get().onHeartbeatReceived(hb);
                  // Mirror the leader's live connection counts so replicas show the same numbers.
                  if (hb.senderId() == currentLeaderId.get()) {
                    leaderClientCount.set(hb.connectedClients());
                    leaderReplicaCount.set(hb.connectedReplicas());
                  }
                }
                case Message.ElectionInquiry e -> electionRef.get().onElectionInquiry(e);
                case Message.Answer a -> electionRef.get().onAnswer(a);
                case Message.IAmLeader leader -> electionRef.get().onCoordinator(leader);
                default -> {}
              }
            });

    // TCP layer: one port serves both chat clients (CLIENT role) and replicas (SERVER role).
    TcpServer tcpServer =
        new TcpServer(
            config,
            (msg, session) -> {
              switch (msg) {
                case HistoryRequest ignored ->
                    // Replica joining or rejoining: send the full history snapshot.
                    session.send(
                        new HistorySnapshot(
                            myId,
                            SenderRole.SERVER,
                            System.currentTimeMillis(),
                            chatHistory.snapshot()));

                case Chat chatMsg when currentLeaderId.get() == myId -> {
                  // Leader accepts a chat from a client.
                  ChatEntry entry = new ChatEntry(chatMsg.from(), chatMsg.text(), chatMsg.ts());
                  chatHistory.append(entry);
                  // Fan the message back to all connected clients.
                  tcpServerRef
                      .get()
                      .broadcastToClients(
                          new Chat(
                              myId,
                              SenderRole.SERVER,
                              System.currentTimeMillis(),
                              chatMsg.from(),
                              chatMsg.text()));
                  // Push a delta to all connected replicas.
                  tcpServerRef
                      .get()
                      .broadcastToReplicas(
                          new StateSync(
                              myId, SenderRole.SERVER, System.currentTimeMillis(), List.of(entry)));
                  log.info(
                      "event=chat_accepted myId={} from={} historySize={}",
                      myId,
                      chatMsg.from(),
                      chatHistory.size());
                }

                default ->
                    log.debug(
                        "event=tcp_msg_ignored myId={} type={}",
                        myId,
                        msg.getClass().getSimpleName());
              }
            });

    // When leader changes: notify TCP clients/replicas and reconnect outgoing replica link.
    ElectionService election =
        new ElectionService(
            config,
            groupView,
            discovery::send,
            currentLeaderId,
            newLeaderId -> {
              tcpServerRef.get().notifyLeaderChange(newLeaderId);
              replicaConnRef.get().reconnect(newLeaderId);
            });

    HeartbeatService heartbeat =
        new HeartbeatService(
            config,
            groupView,
            discovery::send,
            deadPeerId -> {
              if (deadPeerId == currentLeaderId.get()) {
                election.startElection();
              }
            },
            tcpServer::clientCount,
            tcpServer::replicaCount);

    ReplicaConnector replicaConnector =
        new ReplicaConnector(config, groupView, currentLeaderId, chatHistory);

    electionRef.set(election);
    heartbeatRef.set(heartbeat);
    tcpServerRef.set(tcpServer);
    replicaConnRef.set(replicaConnector);

    try {
      discovery.start();
    } catch (SocketException e) {
      System.err.println("server startup failed: cannot bind discovery port: " + e.getMessage());
      discovery.close();
      System.exit(3);
      return;
    }

    try {
      tcpServer.start();
    } catch (IOException e) {
      System.err.println("server startup failed: cannot bind TCP port: " + e.getMessage());
      tcpServer.close();
      discovery.close();
      System.exit(4);
      return;
    }

    heartbeat.start();

    // Live terminal dashboard: only on an interactive TTY, and never required for the server to
    // run. A piped/CI run (System.console() == null) or any terminal-init failure falls back to the
    // plain stdout logging the tests and demo scripts rely on.
    if (dashboardEnabled()) {
      DashboardEvents events = ServerDashboard.newEventBuffer();
      ServerDashboard dashboard =
          new ServerDashboard(
              myId,
              config.listenHost(),
              config.listenPort(),
              config.discoveryPort(),
              currentLeaderId,
              groupView,
              // Leader shows its own live counts; a replica shows the leader's (from its
              // heartbeats), so every panel displays the same cluster-wide numbers.
              () ->
                  currentLeaderId.get() == myId ? tcpServer.clientCount() : leaderClientCount.get(),
              () ->
                  currentLeaderId.get() == myId
                      ? tcpServer.replicaCount()
                      : leaderReplicaCount.get(),
              chatHistory::size,
              events);
      try {
        dashboard.start();
        DashboardLogRouting.install(events, myId);
        dashboardRef.set(dashboard);
      } catch (Throwable t) {
        dashboard.close();
        log.warn("event=dashboard_unavailable reason={}", t.toString());
      }
    }

    // No prior leader exists on a cold start, so kick off a one-shot election once the group view
    // has had time to converge; the highest live id becomes the initial leader.
    election.scheduleBootstrap();

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  // Restore the terminal first so the shell is usable again right away.
                  ServerDashboard dashboard = dashboardRef.get();
                  if (dashboard != null) {
                    dashboard.close();
                  }
                  replicaConnector.close();
                  heartbeat.close();
                  election.close();
                  tcpServer.close();
                  discovery.close();
                  log.info("event=shutdown serverId={}", myId);
                }));

    log.info("event=ready serverId={}", myId);

    park();
  }

  private static void park() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * The live dashboard runs only on an interactive terminal. It can be turned off (plain logs come
   * back) via {@code CHATAPP_DASHBOARD=off} or {@code -Dchatapp.dashboard=off}; both are optional
   * overrides, so a bare {@code java -jar chatapp.jar server} still just works.
   */
  private static boolean dashboardEnabled() {
    String pref = System.getenv("CHATAPP_DASHBOARD");
    if (pref == null) {
      pref = System.getProperty("chatapp.dashboard");
    }
    if (pref != null
        && (pref.equalsIgnoreCase("off")
            || pref.equalsIgnoreCase("plain")
            || pref.equalsIgnoreCase("false"))) {
      return false;
    }
    return System.console() != null;
  }
}
