package com.chatapp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chatapp.protocol.Message.ChatEntry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatHistory}: append, delta application, snapshot reset, and immutability
 * of returned snapshots.
 */
class ChatHistoryTest {

  private ChatHistory history;

  @BeforeEach
  void setUp() {
    history = new ChatHistory();
  }

  @Test
  void appendIncreasesSize() {
    history.append(entry("alice", "hello"));
    history.append(entry("bob", "hi"));
    assertEquals(2, history.size());
  }

  @Test
  void snapshotReflectsAllEntries() {
    history.append(entry("alice", "first"));
    history.append(entry("bob", "second"));
    List<ChatEntry> snap = history.snapshot();
    assertEquals(2, snap.size());
    assertEquals("first", snap.get(0).text());
    assertEquals("second", snap.get(1).text());
  }

  @Test
  void snapshotIsImmutable() {
    history.append(entry("alice", "msg"));
    List<ChatEntry> snap = history.snapshot();
    // Modifying the snapshot must not affect the stored history.
    assertNotSame(history.snapshot(), snap, "each call returns a fresh copy");
    history.append(entry("bob", "extra"));
    assertEquals(1, snap.size(), "old snapshot is not affected by later appends");
    assertEquals(2, history.size());
  }

  @Test
  void applyDeltaAppendsNewEntries() {
    history.append(entry("alice", "old"));
    history.applyDelta(List.of(entry("bob", "new1"), entry("carl", "new2")));
    assertEquals(3, history.size());
    assertEquals("new2", history.snapshot().get(2).text());
  }

  @Test
  void resetFromSnapshotReplacesAllEntries() {
    history.append(entry("alice", "stale"));
    history.resetFromSnapshot(List.of(entry("leader", "fresh1"), entry("leader", "fresh2")));
    assertEquals(2, history.size());
    assertEquals("fresh1", history.snapshot().get(0).text());
  }

  @Test
  void emptyHistoryReturnsEmptySnapshot() {
    assertTrue(history.snapshot().isEmpty());
    assertEquals(0, history.size());
  }

  @Test
  void historyPreservesOrder() {
    for (int i = 0; i < 5; i++) {
      history.append(entry("user", "msg" + i));
    }
    List<ChatEntry> snap = history.snapshot();
    for (int i = 0; i < 5; i++) {
      assertEquals("msg" + i, snap.get(i).text());
    }
  }

  private static ChatEntry entry(String from, String text) {
    return new ChatEntry(from, text, System.currentTimeMillis());
  }
}
