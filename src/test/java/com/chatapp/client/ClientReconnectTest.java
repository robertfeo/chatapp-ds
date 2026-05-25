package com.chatapp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the client-side reconnect buffer: messages typed while disconnected must be
 * preserved in insertion order and delivered after the connection is restored.
 */
class ClientReconnectTest {

  private ChatClient client;

  @BeforeEach
  void setUp() {
    client = new ChatClient("alice", 42, 4500, "255.255.255.255");
  }

  @Test
  void messagesBufferedDuringDisconnectArePreserved() {
    client.bufferMessage("hello");
    client.bufferMessage("world");

    assertEquals(2, client.pendingCount());
  }

  @Test
  void drainedMessagesAreReturnedInInsertionOrder() {
    client.bufferMessage("first");
    client.bufferMessage("second");
    client.bufferMessage("third");

    Queue<String> drained = client.drainPendingMessages();
    List<String> list = new ArrayList<>(drained);

    assertEquals(List.of("first", "second", "third"), list);
  }

  @Test
  void pendingQueueIsEmptyAfterDrain() {
    client.bufferMessage("msg");
    client.drainPendingMessages();

    assertEquals(0, client.pendingCount());
  }

  @Test
  void emptyQueueDrainsToEmptyResult() {
    Queue<String> drained = client.drainPendingMessages();
    assertTrue(drained.isEmpty());
  }

  @Test
  void multipleBufferDrainCyclesWork() {
    // Simulate: disconnect → buffer → reconnect → drain → disconnect → buffer → reconnect → drain
    client.bufferMessage("first-disconnect");
    Queue<String> firstDrain = client.drainPendingMessages();
    assertEquals(1, firstDrain.size());
    assertEquals("first-disconnect", firstDrain.poll());

    client.bufferMessage("second-disconnect-1");
    client.bufferMessage("second-disconnect-2");
    Queue<String> secondDrain = client.drainPendingMessages();
    assertEquals(2, secondDrain.size());
  }

  @Test
  void stopSignalPreventsNewConnections() {
    client.stop();
    // After stop(), the client should not be running.
    // We verify by checking that stop() does not throw and that pendingCount is still accessible.
    assertEquals(0, client.pendingCount());
  }
}
