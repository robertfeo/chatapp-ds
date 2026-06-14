package com.chatapp.election;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.chatapp.config.ServerConfig;
import com.chatapp.discovery.GroupView;
import com.chatapp.discovery.Peer;
import com.chatapp.protocol.Message.IAmLeader;
import com.chatapp.protocol.Message.SenderRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ElectionService#onCoordinator} correctly updates all replicas so every alive
 * server converges on the same {@code leader_id} (Issue #11 acceptance criterion 1).
 */
class LeaderAnnouncementTest {

  private static final ServerConfig CONFIG =
      new ServerConfig(1, "127.0.0.1", 6001, 4500, "255.255.255.255");

  private AtomicInteger leaderId;
  private GroupView groupView;

  @BeforeEach
  void setUp() {
    leaderId = new AtomicInteger(-1);
    groupView = new GroupView();
    groupView.upsert(new Peer(1, "127.0.0.1", 6001));
    groupView.upsert(new Peer(2, "127.0.0.1", 6002));
    groupView.upsert(new Peer(3, "127.0.0.1", 6003));
  }

  @Test
  void onCoordinatorSetsLeaderId() {
    ElectionService service = new ElectionService(CONFIG, groupView, (m, a) -> {}, leaderId);

    service.onCoordinator(iAmLeader(2, 2));

    assertEquals(2, leaderId.get());
  }

  @Test
  void multipleReplicasConvergeOnSameLeaderId() {
    // Simulate three independent ElectionService instances (one per server).
    AtomicInteger leaderId1 = new AtomicInteger(-1);
    AtomicInteger leaderId2 = new AtomicInteger(-1);
    AtomicInteger leaderId3 = new AtomicInteger(-1);

    ServerConfig cfg1 = new ServerConfig(1, "127.0.0.1", 6001, 4500, "255.255.255.255");
    ServerConfig cfg2 = new ServerConfig(2, "127.0.0.1", 6002, 4500, "255.255.255.255");
    ServerConfig cfg3 = new ServerConfig(3, "127.0.0.1", 6003, 4500, "255.255.255.255");

    ElectionService s1 = new ElectionService(cfg1, groupView, (m, a) -> {}, leaderId1);
    ElectionService s2 = new ElectionService(cfg2, groupView, (m, a) -> {}, leaderId2);
    ElectionService s3 = new ElectionService(cfg3, groupView, (m, a) -> {}, leaderId3);

    // Server 3 won the election and announces itself.
    IAmLeader announcement = iAmLeader(3, 3);
    s1.onCoordinator(announcement);
    s2.onCoordinator(announcement);
    s3.onCoordinator(announcement);

    assertEquals(3, leaderId1.get(), "server 1 must converge on leader=3");
    assertEquals(3, leaderId2.get(), "server 2 must converge on leader=3");
    assertEquals(3, leaderId3.get(), "server 3 must converge on leader=3");
  }

  @Test
  void onCoordinatorStopsRunningElection() {
    // Trigger an election, then announce a winner before the voting window closes.
    List<Object> sent = new ArrayList<>();
    ElectionService service =
        new ElectionService(CONFIG, groupView, (m, a) -> sent.add(m), leaderId);

    service.startElection();
    // Election is now running; immediately announce a winner from outside.
    service.onCoordinator(iAmLeader(2, 2));

    // The service must have accepted the leader even while an election was running.
    assertEquals(2, leaderId.get());
  }

  @Test
  void onLeaderChangedCallbackIsInvoked() {
    List<Integer> notified = new ArrayList<>();
    ElectionService service =
        new ElectionService(CONFIG, groupView, (m, a) -> {}, leaderId, notified::add);

    service.onCoordinator(iAmLeader(2, 2));

    assertFalse(notified.isEmpty(), "onLeaderChanged callback must be called");
    assertEquals(2, notified.get(0));
  }

  private static IAmLeader iAmLeader(int senderId, int newLeaderId) {
    return new IAmLeader(senderId, SenderRole.SERVER, System.currentTimeMillis(), newLeaderId);
  }
}
