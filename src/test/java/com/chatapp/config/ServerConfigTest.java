package com.chatapp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerConfigTest {

  private static Function<String, String> env(Map<String, String> values) {
    return values::get;
  }

  @Test
  void appliesDefaultsWhenOnlyServerIdIsSet() throws ConfigException {
    ServerConfig config = ServerConfig.from(env(Map.of("SERVER_ID", "3")));

    assertEquals(3, config.serverId());
    assertEquals(Config.DEFAULT_LISTEN_HOST, config.listenHost());
    assertEquals(Config.DEFAULT_LISTEN_PORT, config.listenPort());
    assertEquals(Config.DEFAULT_DISCOVERY_PORT, config.discoveryPort());
    assertEquals(Config.DEFAULT_BROADCAST_ADDR, config.broadcastAddr());
  }

  @Test
  void parsesAllExplicitValues() throws ConfigException {
    ServerConfig config =
        ServerConfig.from(
            env(
                Map.of(
                    "SERVER_ID", "2",
                    "LISTEN_HOST", "10.0.0.2",
                    "LISTEN_PORT", "6002",
                    "DISCOVERY_PORT", "4600",
                    "BROADCAST_ADDR", "10.0.0.255")));

    assertEquals(2, config.serverId());
    assertEquals("10.0.0.2", config.listenHost());
    assertEquals(6002, config.listenPort());
    assertEquals(4600, config.discoveryPort());
    assertEquals("10.0.0.255", config.broadcastAddr());
  }

  @Test
  void missingServerIdFailsFastWithClearMessage() {
    ConfigException e = assertThrows(ConfigException.class, () -> ServerConfig.from(env(Map.of())));
    assertTrue(e.getMessage().contains("SERVER_ID"), "message should name the missing key");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "abc", "3.5"})
  void rejectsNonNumericOrBlankServerId(String bad) {
    assertThrows(
        ConfigException.class, () -> ServerConfig.from(k -> "SERVER_ID".equals(k) ? bad : null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "70000", "notaport"})
  void rejectsInvalidListenPort(String bad) {
    assertThrows(
        ConfigException.class,
        () -> ServerConfig.from(env(Map.of("SERVER_ID", "1", "LISTEN_PORT", bad))));
  }

  @Test
  void blankOptionalValueFallsBackToDefault() throws ConfigException {
    ServerConfig config = ServerConfig.from(env(Map.of("SERVER_ID", "1", "LISTEN_HOST", "   ")));
    assertEquals(Config.DEFAULT_LISTEN_HOST, config.listenHost());
  }
}
