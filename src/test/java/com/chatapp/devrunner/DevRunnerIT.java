package com.chatapp.devrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@code scripts/dev.sh}: a cluster comes up as background JVMs and, crucially, {@code
 * down} leaves no orphan processes. Runs servers only (clients need #7) on a private discovery port
 * so it does not collide with anything else.
 */
class DevRunnerIT {

  private static final File JAR = new File("target/chatapp.jar");
  private static final File SCRIPT = new File("scripts/dev.sh");

  @Test
  void upStartsServersAndDownLeavesNoOrphans() throws IOException, InterruptedException {
    if (!JAR.isFile()) {
      fail("missing " + JAR + "; the verify lifecycle packages it before integration tests");
    }
    Path runDir = Files.createTempDirectory("dev-it");
    int discoveryPort = freeUdpPort();

    List<Long> pids;
    try {
      assertEquals(0, run("up", runDir, discoveryPort), "dev.sh up should succeed");

      pids = readPids(runDir.resolve("pids"));
      assertEquals(2, pids.size(), "two servers should have been started");
      // Give the JVMs a moment to actually be running.
      Thread.sleep(500);
      for (long pid : pids) {
        assertTrue(isAlive(pid), "server pid " + pid + " should be alive after up");
      }
    } finally {
      run("down", runDir, discoveryPort);
    }

    // The whole point: after down, none of the started processes survive.
    Thread.sleep(500);
    for (long pid : pids) {
      assertTrue(!isAlive(pid), "server pid " + pid + " must be gone after down (no orphans)");
    }
  }

  private static int run(String command, Path runDir, int discoveryPort)
      throws IOException, InterruptedException {
    ProcessBuilder pb =
        new ProcessBuilder("bash", SCRIPT.getPath(), command).redirectErrorStream(true).inheritIO();
    pb.environment().put("SERVERS", "2");
    pb.environment().put("CLIENTS", "0");
    pb.environment().put("DISCOVERY_PORT", Integer.toString(discoveryPort));
    pb.environment().put("RUN_DIR", runDir.toString());
    Process process = pb.start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("dev.sh " + command + " did not finish in time");
    }
    return process.exitValue();
  }

  private static List<Long> readPids(Path pidFile) throws IOException {
    return Files.readAllLines(pidFile).stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Long::parseLong)
        .toList();
  }

  private static boolean isAlive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private static int freeUdpPort() throws IOException {
    try (DatagramSocket probe = new DatagramSocket(0)) {
      return probe.getLocalPort();
    }
  }
}
