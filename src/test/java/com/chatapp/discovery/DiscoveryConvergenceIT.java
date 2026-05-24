package com.chatapp.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chatapp.config.ServerConfig;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real UDP-broadcast discovery between several {@link DiscoveryService} instances on loopback (they
 * share one discovery port via SO_REUSEPORT, as the localhost dev setup does). Validates that every
 * server converges on the same group view, including a server that joins late.
 */
class DiscoveryConvergenceIT {

  private final List<DiscoveryService> services = new ArrayList<>();

  @AfterEach
  void stopAll() {
    services.forEach(DiscoveryService::close);
    services.clear();
  }

  @Test
  void serversConvergeOnTheSameGroupViewIncludingLateJoiners() throws Exception {
    int discoveryPort = freeUdpPort();

    GroupView v1 = startServer(1, discoveryPort, 6001);
    GroupView v2 = startServer(2, discoveryPort, 6002);
    GroupView v3 = startServer(3, discoveryPort, 6003);

    awaitConvergence(Set.of(1, 2, 3), v1, v2, v3);

    // A fourth server joins later: everyone, including it, must end up with all four.
    GroupView v4 = startServer(4, discoveryPort, 6004);
    awaitConvergence(Set.of(1, 2, 3, 4), v1, v2, v3, v4);
  }

  private GroupView startServer(int id, int discoveryPort, int listenPort) throws SocketException {
    ServerConfig config =
        new ServerConfig(id, "127.0.0.1", listenPort, discoveryPort, "255.255.255.255");
    GroupView view = new GroupView();
    view.upsert(new Peer(id, "127.0.0.1", listenPort)); // seed self, exactly as ServerMain does
    DiscoveryService service = new DiscoveryService(config, view, () -> null);
    service.start();
    services.add(service);
    return view;
  }

  /** Re-announce while polling so the test is robust against startup races, up to a deadline. */
  private void awaitConvergence(Set<Integer> expected, GroupView... views)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (System.nanoTime() < deadline) {
      for (DiscoveryService service : services) {
        try {
          service.announce();
        } catch (IOException ignored) {
          // transient; the next round retries
        }
      }
      Thread.sleep(150);
      if (Arrays.stream(views).allMatch(v -> v.ids().equals(expected))) {
        return;
      }
    }
    for (GroupView view : views) {
      assertEquals(expected, view.ids(), "a group view did not converge within the deadline");
    }
  }

  private static int freeUdpPort() throws SocketException {
    try (DatagramSocket probe = new DatagramSocket(0)) {
      return probe.getLocalPort();
    }
  }
}
