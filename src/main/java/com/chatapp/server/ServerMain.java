package com.chatapp.server;

import com.chatapp.config.Config;
import com.chatapp.config.ConfigException;
import com.chatapp.config.ServerConfig;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Entry point for a server process: {@code java -jar chatapp.jar server}.
 *
 * <p>For now it loads and validates configuration, prints a startup banner, and parks so the
 * process behaves like a long-running server you stop with Ctrl+C. Discovery, heartbeats, election
 * and the chat path are wired in across the M1/M2 issues.
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

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> log.info("event=shutdown serverId={}", config.serverId())));

    log.info(
        "event=ready serverId={} note=\"subsystems (discovery, heartbeat, election, chat)"
            + " land in later issues\"",
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
