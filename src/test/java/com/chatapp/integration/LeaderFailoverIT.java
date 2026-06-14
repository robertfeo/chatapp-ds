package com.chatapp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * End-to-end proof of failover (issue #15): three in-process server stacks elect the highest id as
 * leader, the leader is then killed, and the surviving cluster must elect the next-highest survivor
 * with no manual intervention.
 *
 * <p>In-process (not spawned JVMs) for the same reason as {@code ColdStartElectionIT}: the servers
 * share one discovery port via SO_REUSEPORT, where only broadcast fans out to all of them, and
 * discovery + Bully election are broadcast, so they are exercised faithfully here. The one thing
 * loopback cannot reproduce is unicast-heartbeat <em>death detection</em> (a unicast to the shared
 * port reaches only one socket), so the leader-death event is injected exactly as the real {@code
 * HeartbeatService} delivers it: the dead peer is removed from the group view and, because it was
 * the leader, an election is started. Heartbeat detection itself is validated on the demo hardware
 * (see {@code docs/manual_test_plan.md}, scenario 3).
 */
class LeaderFailoverIT {

  private final List<Stack> stacks = new ArrayList<>();

  @AfterEach
  void stopAll() {
    stacks.forEach(Stack::close);
    stacks.clear();
  }

  @Test
  void killingTheLeaderElectsTheNextHighestSurvivor() throws Exception {
    int discoveryPort = freeUdpPort();

    Stack s1 = startServer(1, discoveryPort, 6001);
    Stack s2 = startServer(2, discoveryPort, 6002);
    Stack s3 = startServer(3, discoveryPort, 6003);

    // Cold start: the whole cluster converges on the highest id (3) as leader.
    awaitGroupConvergence(Duration.ofSeconds(8), s1, s2, s3);
    stacks.forEach(st -> st.election.scheduleBootstrap());
    awaitLeader(3, Duration.ofSeconds(15), s1, s2, s3);

    // Kill the leader (id 3): stop it participating entirely.
    s3.close();
    stacks.remove(s3);

    // The survivors detect the leader is gone. This mirrors HeartbeatService.checkLiveness +
    // ServerMain's onPeerDead wiring: drop the dead peer from the group view, and if it was the
    // leader, start an election.
    for (Stack survivor : List.of(s1, s2)) {
      survivor.view.remove(3);
      if (survivor.leaderId.get() == 3) {
        survivor.election.startElection();
      }
    }

    // The next-highest survivor (id 2) must take over on every remaining server.
    awaitLeader(2, Duration.ofSeconds(15), s1, s2);

    assertEquals(2, s1.leaderId.get(), "id 1 must follow the new leader");
    assertEquals(2, s2.leaderId.get(), "id 2 must lead after the old leader died");
    assertFalse(s1.view.ids().contains(3), "the dead leader must be gone from the group view");
    assertFalse(s2.view.ids().contains(3), "the dead leader must be gone from the group view");
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
      boolean all = true;
      for (Stack s : servers) {
        if (s.leaderId.get() != expectedLeader) {
          all = false;
          break;
        }
      }
      if (all) {
        return;
      }
      Thread.sleep(100);
    }
    for (Stack s : servers) {
      assertEquals(
          expectedLeader,
          s.leaderId.get(),
          "every live server must converge on leader=" + expectedLeader);
    }
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
