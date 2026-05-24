package com.chatapp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Smoke test that proves the JUnit pipeline actually runs in CI and guards the toolchain version.
 * Real per-feature tests live next to their features (protocol, election, discovery, ...).
 */
class BuildSmokeTest {

  @Test
  void runsOnJava21OrLater() {
    int major = Runtime.version().feature();
    assertTrue(major >= 21, "chatapp-ds targets Java 21, but is running on Java " + major);
  }
}
