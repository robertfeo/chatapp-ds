package com.chatapp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.DiscoveryService;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.Peer;
import com.chatapp.election.ElectionService;
import com.chatapp.protocol.Message;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Cold-start leader election across several in-process server stacks on loopback, wired exactly as
 * {@code ServerMain} wires them (discovery feeds votes and announcements to election). A freshly
 * booted cluster has no prior leader to die, so this exercises the one-shot bootstrap election:
 * every server must converge on the highest live id as the initial leader.
 *
 * <p>Runs in-process (not as spawned JVMs) for the same reason {@code DiscoveryConvergenceIT} does:
 * the servers share one discovery port via SO_REUSEPORT, where only broadcast traffic fans out to
 * all of them reliably. Discovery, votes and announcements are all broadcast, so this is faithful.
 * The kill-the-leader failover path relies on unicast heartbeats, which do not fan out under
 * SO_REUSEPORT on localhost, so failover is validated manually on the demo hardware instead.
 */
class ColdStartElectionIT {

  private final List<Stack> stacks = new ArrayList<>();

  @AfterEach
  void stopAll() {
    stacks.forEach(Stack::close);
    stacks.clear();
  }

  @Test
  void coldStartElectsTheHighestIdAsLeader() throws Exception {
    int discoveryPort = freeUdpPort();

    Stack s1 = startServer(1, discoveryPort, 6001);
    Stack s2 = startServer(2, discoveryPort, 6002);
    Stack s3 = startServer(3, discoveryPort, 6003);

    // Let discovery converge, then kick off the cold-start election on every server.
    awaitGroupConvergence(Duration.ofSeconds(8), s1, s2, s3);
    stacks.forEach(s -> s.election.scheduleBootstrap());

    // The highest live id (3) must win on every server.
    awaitLeader(3, Duration.ofSeconds(15), s1, s2, s3);
  }

  private Stack startServer(int id, int discoveryPort, int listenPort) throws SocketException {
    ServerConfig config =
        new ServerConfig(id, "127.0.0.1", listenPort, discoveryPort, "255.255.255.255");
    GroupView view = new GroupView();
    view.upsert(new Peer(id, "127.0.0.1", listenPort)); // seed self, exactly as ServerMain does

    AtomicInteger leaderId = new AtomicInteger(-1);
    AtomicReference<ElectionService> electionRef = new AtomicReference<>();

    DiscoveryService discovery =
        new DiscoveryService(
            config,
            view,
            () -> {
              int v = leaderId.get();
              return v == -1 ? null : v;
            },
            msg -> {
              switch (msg) {
                case Message.ElectionInquiry e -> electionRef.get().onElectionInquiry(e);
                case Message.Answer a -> electionRef.get().onAnswer(a);
                case Message.IAmLeader leader -> electionRef.get().onCoordinator(leader);
                default -> {}
              }
            });

    ElectionService election = new ElectionService(config, view, discovery::send, leaderId);
    electionRef.set(election);

    discovery.start();
    Stack stack = new Stack(discovery, election, view, leaderId);
    stacks.add(stack);
    return stack;
  }

  private void awaitGroupConvergence(Duration timeout, Stack... servers)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      for (Stack s : stacks) {
        try {
          s.discovery.announce();
        } catch (Exception ignored) {
          // transient; the next round retries
        }
      }
      Thread.sleep(150);
      if (stacks.stream().allMatch(s -> s.view.size() == servers.length)) {
        return;
      }
    }
  }

  private void awaitLeader(int expectedLeader, Duration timeout, Stack... servers)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (allBelieve(expectedLeader, servers)) {
        return;
      }
      Thread.sleep(100);
    }
    for (Stack s : servers) {
      assertEquals(
          expectedLeader,
          s.leaderId.get(),
          "every server must converge on leader=" + expectedLeader);
    }
  }

  private static boolean allBelieve(int leader, Stack... servers) {
    for (Stack s : servers) {
      if (s.leaderId.get() != leader) {
        return false;
      }
    }
    return true;
  }

  private static int freeUdpPort() throws SocketException {
    try (DatagramSocket probe = new DatagramSocket(0)) {
      return probe.getLocalPort();
    }
  }

  /** One server's in-process service stack. */
  private static final class Stack {
    final DiscoveryService discovery;
    final ElectionService election;
    final GroupView view;
    final AtomicInteger leaderId;

    Stack(
        DiscoveryService discovery,
        ElectionService election,
        GroupView view,
        AtomicInteger leaderId) {
      this.discovery = discovery;
      this.election = election;
      this.view = view;
      this.leaderId = leaderId;
    }

    void close() {
      election.close();
      discovery.close();
    }
  }
}
