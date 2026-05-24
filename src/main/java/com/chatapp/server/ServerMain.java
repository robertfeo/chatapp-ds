package com.chatapp.server;

import com.chatapp.config.Config;
import com.chatapp.config.ConfigException;
import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.DiscoveryService;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.Peer;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Entry point for a server process: {@code java -jar chatapp.jar server}.
 *
 * <p>It loads and validates configuration, prints a startup banner, starts UDP-broadcast discovery,
 * and parks so the process behaves like a long-running server you stop with Ctrl+C. Heartbeats,
 * election and the chat path are wired in across the remaining M1/M2 issues.
 */
public final class ServerMain {

  private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

  private ServerMain() {}

  public static void main(String[] args) {
    ServerConfig config;
    try {
      config = ServerConfig.fromEnv();
    } catch (ConfigException e) {
      // Fail fast with a clear message on stderr; do not start a half-configured server.
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

    // Build the group view, seed it with ourselves, and start discovery.
    GroupView groupView = new GroupView();
    groupView.upsert(new Peer(config.serverId(), config.listenHost(), config.listenPort()));

    // Leader election lands in #10; until then the leader is unknown.
    DiscoveryService discovery = new DiscoveryService(config, groupView, () -> null);
    try {
      discovery.start();
    } catch (SocketException e) {
      System.err.println("server startup failed: cannot bind discovery port: " + e.getMessage());
      discovery.close();
      System.exit(3);
      return;
    }

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  discovery.close();
                  log.info("event=shutdown serverId={}", config.serverId());
                }));

    log.info(
        "event=ready serverId={} note=\"heartbeat, election and chat land in later issues\"",
        config.serverId());

    park();
  }

  /** Block the main thread until the JVM is asked to stop (Ctrl+C / SIGTERM runs the hook). */
  private static void park() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
