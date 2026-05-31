package com.chatapp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.chatapp.discovery.Peer;
import com.chatapp.protocol.Message.DiscoveryReply;
import com.chatapp.protocol.Message.SenderRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatClient#selectLeader}: the pure leader-selection logic that picks the
 * right {@link Peer} from a map of UDP discovery replies.
 *
 * <p>These tests cover Issue #7 acceptance criteria: on startup the client must correctly identify
 * the current leader from broadcast replies before opening a TCP connection.
 */
class ClientDiscoveryTest {

  private static DiscoveryReply reply(int senderId, String host, int port, Integer leaderId) {
    return new DiscoveryReply(
        senderId, SenderRole.SERVER, System.currentTimeMillis(), host, port, leaderId);
  }

  @Test
  void returnsNullWhenNoReplies() {
    assertNull(ChatClient.selectLeader(Map.of()));
  }

  @Test
  void returnsNullWhenNoReplyHasKnownLeaderAddress() {
    // Only a follower replied and points to a leader that did not respond.
    Map<Integer, DiscoveryReply> replies = Map.of(1, reply(1, "10.0.0.1", 6001, 2));
    assertNull(ChatClient.selectLeader(replies));
  }

  @Test
  void prefersReplyWhereServerIsItsOwnLeader() {
    Map<Integer, DiscoveryReply> replies =
        Map.of(
            1, reply(1, "10.0.0.1", 6001, 3),
            2, reply(2, "10.0.0.2", 6002, 3),
            3, reply(3, "10.0.0.3", 6003, 3));

    Peer leader = ChatClient.selectLeader(replies);

    assertEquals(3, leader.id());
    assertEquals("10.0.0.3", leader.host());
    assertEquals(6003, leader.port());
  }

  @Test
  void singleNodeClusterReturnsItself() {
    Map<Integer, DiscoveryReply> replies = Map.of(7, reply(7, "192.168.1.10", 7001, 7));

    Peer leader = ChatClient.selectLeader(replies);

    assertEquals(7, leader.id());
    assertEquals("192.168.1.10", leader.host());
    assertEquals(7001, leader.port());
  }

  @Test
  void fallbackPicksAddressFromFollowerReportWhenLeaderHasStaleLeaderId() {
    // Server 1 (follower) reports new leader=5. Server 5 replies but still points to old leader 3.
    // First loop: 1!=5, 5!=3 -> no match. Fallback: reply(1,leaderId=5), key 5 exists -> server 5.
    Map<Integer, DiscoveryReply> replies =
        Map.of(
            1, reply(1, "10.0.0.1", 6001, 5),
            5, reply(5, "10.0.0.5", 6005, 3));

    Peer leader = ChatClient.selectLeader(replies);

    assertEquals(5, leader.id());
    assertEquals("10.0.0.5", leader.host());
    assertEquals(6005, leader.port());
  }

  @Test
  void stopSignalDoesNotThrow() {
    ChatClient client = new ChatClient("tester", 99, 4500, "255.255.255.255");
    client.stop();
    client.stop();
  }
}
