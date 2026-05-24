package com.chatapp.config;

/**
 * Project-wide constants. Timing values are fixed by the agreed design; the {@code DEFAULT_*}
 * values are fallbacks used when the matching environment variable is not set.
 */
public final class Config {

  private Config() {}

  /** Interval between heartbeats a server sends to each peer, in seconds. */
  public static final int HEARTBEAT_INTERVAL_S = 2;

  /** A peer with no heartbeat for this many seconds is declared dead. */
  public static final int PEER_DEAD_TIMEOUT_S = 6;

  /** How long an election round collects votes before the highest id wins, in seconds. */
  public static final int ELECTION_TIMEOUT_S = 2;

  /** How often a server re-announces itself for discovery, in seconds. */
  public static final int DISCOVERY_REANNOUNCE_S = 10;

  /** Default bind address when {@code LISTEN_HOST} is not set. */
  public static final String DEFAULT_LISTEN_HOST = "0.0.0.0";

  /** Default TCP chat port when {@code LISTEN_PORT} is not set. */
  public static final int DEFAULT_LISTEN_PORT = 6000;

  /** Default UDP discovery/heartbeat port when {@code DISCOVERY_PORT} is not set. */
  public static final int DEFAULT_DISCOVERY_PORT = 4500;

  /** Default subnet broadcast address when {@code BROADCAST_ADDR} is not set. */
  public static final String DEFAULT_BROADCAST_ADDR = "255.255.255.255";
}
