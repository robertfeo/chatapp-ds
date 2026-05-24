package com.chatapp.server;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end checks of the {@code java -jar chatapp.jar server} entry point against the shaded
 * fat-jar built in the package phase.
 */
class ServerBootstrapIT {

  private static final File JAR = new File("target/chatapp.jar");
  private static final String JAVA =
      System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

  @Test
  void failsFastWhenServerIdIsMissing() throws IOException, InterruptedException {
    requireJar();
    Process process = launch(Map.of(), /* removeServerId= */ true);
    String output = drain(process);

    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "process should exit quickly, not hang");
    assertNotEquals(0, process.exitValue(), "missing SERVER_ID must fail fast with non-zero exit");
    assertTrue(
        output.contains("SERVER_ID"), "error should name the missing variable, was:\n" + output);
  }

  @Test
  void logsStartupBannerWhenConfigured() throws IOException, InterruptedException {
    requireJar();
    Process process =
        launch(
            Map.of(
                "SERVER_ID", "3",
                "LISTEN_PORT", "6003",
                "DISCOVERY_PORT", "4503"),
            false);
    try {
      String line = readUntil(process, "event=startup", Duration.ofSeconds(10));
      assertTrue(line.contains("serverId=3"), "banner should show the server id, was: " + line);
      assertTrue(
          line.contains("listen=0.0.0.0:6003"), "banner should show the listen addr: " + line);
    } finally {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  private static void requireJar() {
    if (!JAR.isFile()) {
      fail("missing " + JAR + "; run `mvn package` (the verify lifecycle does this automatically)");
    }
  }

  private static Process launch(Map<String, String> env, boolean removeServerId)
      throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(JAVA, "-jar", JAR.getPath(), "server").redirectErrorStream(true);
    Map<String, String> environment = pb.environment();
    if (removeServerId) {
      environment.remove("SERVER_ID");
    }
    environment.putAll(new HashMap<>(env));
    return pb.start();
  }

  /** Read all output until the process closes its stream (it has exited). */
  private static String drain(Process process) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append('\n');
      }
      return sb.toString();
    }
  }

  /** Read output lines until one contains {@code needle}, or fail when the deadline passes. */
  private static String readUntil(Process process, String needle, Duration timeout)
      throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    String line;
    while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
      if (line.contains(needle)) {
        return line;
      }
    }
    return fail("did not see '" + needle + "' within " + timeout);
  }
}
