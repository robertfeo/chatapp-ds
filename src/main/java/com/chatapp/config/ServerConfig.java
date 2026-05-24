package com.chatapp.config;

import java.util.function.Function;

/**
 * A server's configuration, loaded entirely from environment variables so the same fat-jar runs
 * unchanged on every host.
 *
 * <ul>
 *   <li>{@code SERVER_ID} (required): unique numeric id; the highest live id becomes leader.
 *   <li>{@code LISTEN_HOST} (default {@value Config#DEFAULT_LISTEN_HOST}): TCP bind address.
 *   <li>{@code LISTEN_PORT} (default {@value Config#DEFAULT_LISTEN_PORT}): TCP chat/state-sync
 *       port.
 *   <li>{@code DISCOVERY_PORT} (default {@value Config#DEFAULT_DISCOVERY_PORT}): UDP port.
 *   <li>{@code BROADCAST_ADDR} (default {@value Config#DEFAULT_BROADCAST_ADDR}): discovery target.
 * </ul>
 */
public record ServerConfig(
    int serverId, String listenHost, int listenPort, int discoveryPort, String broadcastAddr) {

  /** Load from the real process environment. */
  public static ServerConfig fromEnv() throws ConfigException {
    return from(System::getenv);
  }

  /**
   * Load from an arbitrary environment lookup, so the parsing and validation can be unit tested
   * without touching the real process environment.
   */
  public static ServerConfig from(Function<String, String> env) throws ConfigException {
    int serverId = requiredInt(env, "SERVER_ID");
    String listenHost = orDefault(env.apply("LISTEN_HOST"), Config.DEFAULT_LISTEN_HOST);
    int listenPort = portOrDefault(env, "LISTEN_PORT", Config.DEFAULT_LISTEN_PORT);
    int discoveryPort = portOrDefault(env, "DISCOVERY_PORT", Config.DEFAULT_DISCOVERY_PORT);
    String broadcastAddr = orDefault(env.apply("BROADCAST_ADDR"), Config.DEFAULT_BROADCAST_ADDR);
    return new ServerConfig(serverId, listenHost, listenPort, discoveryPort, broadcastAddr);
  }

  private static int requiredInt(Function<String, String> env, String key) throws ConfigException {
    String raw = env.apply(key);
    if (raw == null || raw.isBlank()) {
      throw new ConfigException(key + " is required (set it as an environment variable)");
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new ConfigException(key + " must be an integer, got '" + raw + "'");
    }
  }

  private static int portOrDefault(Function<String, String> env, String key, int fallback)
      throws ConfigException {
    String raw = env.apply(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    int port;
    try {
      port = Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new ConfigException(key + " must be an integer, got '" + raw + "'");
    }
    if (port < 1 || port > 65535) {
      throw new ConfigException(key + " must be in 1..65535, got " + port);
    }
    return port;
  }

  private static String orDefault(String value, String fallback) {
    return (value == null || value.isBlank()) ? fallback : value.trim();
  }
}
