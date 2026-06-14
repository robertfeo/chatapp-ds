package com.chatapp.client;

import com.chatapp.config.Config;
import java.net.InetAddress;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Entry point for a chat client: {@code java -jar chatapp.jar client}. Needs no configuration; the
 * optional env vars below only override the automatic defaults.
 *
 * <ul>
 *   <li>{@code CLIENT_NAME} – display name shown in chat (default: this host's name)
 *   <li>{@code DISCOVERY_PORT} – UDP port shared with servers (default: {@value
 *       Config#DEFAULT_DISCOVERY_PORT})
 *   <li>{@code BROADCAST_ADDR} – subnet broadcast address (default: {@value
 *       Config#DEFAULT_BROADCAST_ADDR})
 * </ul>
 */
public final class ClientMain {

  private ClientMain() {}

  public static void main(String[] args) {
    String name = envOrDefault("CLIENT_NAME", defaultName());
    int discoveryPort = ChatClient.resolveDiscoveryPort();
    String broadcastAddr = envOrDefault("BROADCAST_ADDR", Config.DEFAULT_BROADCAST_ADDR);

    // Ephemeral client id: random so multiple clients on the same host don't collide.
    int clientId = ThreadLocalRandom.current().nextInt(1_000_000, Integer.MAX_VALUE);

    ChatClient client = new ChatClient(name, clientId, discoveryPort, broadcastAddr);

    Runtime.getRuntime().addShutdownHook(new Thread(client::stop, "client-shutdown"));

    client.start();
  }

  private static String envOrDefault(String key, String fallback) {
    String value = System.getenv(key);
    return (value == null || value.isBlank()) ? fallback : value.trim();
  }

  /** This host's name, so two clients on different machines are distinguishable without config. */
  private static String defaultName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "anon";
    }
  }
}
