package com.chatapp.config;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A server's configuration, loaded entirely from environment variables so the same fat-jar runs
 * unchanged on every host.
 *
 * <ul>
 *   <li>{@code SERVER_ID} (optional): unique numeric id; the highest live id becomes leader. When
 *       not set it is <b>derived automatically</b> from the host's LAN IPv4 (its last octet), so it
 *       never has to be assigned by hand on the demo hosts. Set it explicitly only to run several
 *       servers on one machine (the localhost dev runner and the tests do this).
 *   <li>{@code LISTEN_HOST} (default {@value Config#DEFAULT_LISTEN_HOST}): TCP bind address.
 *   <li>{@code LISTEN_PORT} (default {@value Config#DEFAULT_LISTEN_PORT}): TCP chat/state-sync
 *       port.
 *   <li>{@code DISCOVERY_PORT} (default {@value Config#DEFAULT_DISCOVERY_PORT}): UDP port.
 *   <li>{@code BROADCAST_ADDR} (default {@value Config#DEFAULT_BROADCAST_ADDR}): discovery target.
 * </ul>
 */
public record ServerConfig(
    int serverId, String listenHost, int listenPort, int discoveryPort, String broadcastAddr) {

  private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

  /**
   * Load from the real process environment. If {@code SERVER_ID} is unset, derive it automatically
   * from the host's LAN address.
   */
  public static ServerConfig fromEnv() throws ConfigException {
    Function<String, String> env = System::getenv;
    String rawId = env.apply("SERVER_ID");
    if (rawId == null || rawId.isBlank()) {
      int autoId = deriveServerId();
      Function<String, String> withAutoId =
          key -> "SERVER_ID".equals(key) ? Integer.toString(autoId) : env.apply(key);
      return from(withAutoId);
    }
    return from(env);
  }

  /**
   * Load from an arbitrary environment lookup, so the parsing and validation can be unit tested
   * without touching the real process environment. {@code SERVER_ID} is required here; the
   * auto-derivation lives in {@link #fromEnv()}.
   */
  public static ServerConfig from(Function<String, String> env) throws ConfigException {
    int serverId = requiredInt(env, "SERVER_ID");
    String listenHost = orDefault(env.apply("LISTEN_HOST"), Config.DEFAULT_LISTEN_HOST);
    int listenPort = portOrDefault(env, "LISTEN_PORT", Config.DEFAULT_LISTEN_PORT);
    int discoveryPort = portOrDefault(env, "DISCOVERY_PORT", Config.DEFAULT_DISCOVERY_PORT);
    String broadcastAddr = orDefault(env.apply("BROADCAST_ADDR"), Config.DEFAULT_BROADCAST_ADDR);
    return new ServerConfig(serverId, listenHost, listenPort, discoveryPort, broadcastAddr);
  }

  /**
   * Derives a unique numeric server id from the host's LAN IPv4 address (its last octet). On one
   * router/subnet every host has a distinct address, hence a distinct id, with no manual
   * assignment; the highest id (the highest IP) becomes the leader.
   */
  static int deriveServerId() throws ConfigException {
    InetAddress ip = primaryLanAddress();
    if (ip == null) {
      throw new ConfigException(
          "could not determine a LAN IPv4 address to derive SERVER_ID from; "
              + "connect to the network, or set SERVER_ID explicitly");
    }
    int lastOctet = ip.getAddress()[3] & 0xFF;
    log.info("event=server_id_auto serverId={} fromIp={}", lastOctet, ip.getHostAddress());
    return lastOctet;
  }

  /** The host's primary LAN IPv4 (the interface that owns the default route), or {@code null}. */
  private static InetAddress primaryLanAddress() {
    // The kernel picks the default-route interface's source address; no packet is sent.
    try (DatagramSocket probe = new DatagramSocket()) {
      probe.connect(InetAddress.getByName("8.8.8.8"), 53);
      InetAddress local = probe.getLocalAddress();
      if (local instanceof Inet4Address
          && !local.isAnyLocalAddress()
          && !local.isLoopbackAddress()) {
        return local;
      }
    } catch (Exception ignored) {
      // fall through to interface enumeration
    }
    try {
      for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nif.isUp() || nif.isLoopback()) {
          continue;
        }
        for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
          if (addr instanceof Inet4Address
              && !addr.isLoopbackAddress()
              && !addr.isLinkLocalAddress()) {
            return addr;
          }
        }
      }
    } catch (SocketException ignored) {
      // no usable interface
    }
    return null;
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
