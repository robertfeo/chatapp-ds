package com.chatapp.server;

import com.chatapp.protocol.Message.ChatEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe, append-only chat history shared between the leader and replica roles.
 *
 * <p>The leader appends every accepted {@link com.chatapp.protocol.Message.Chat} message and fans
 * the delta to replicas via {@code STATE_SYNC}. Replicas apply incoming deltas and reset from a
 * {@code HISTORY_SNAPSHOT} when they (re)join.
 */
public final class ChatHistory {

  private final List<ChatEntry> entries = new ArrayList<>();

  /** Append a single new entry. Thread-safe. */
  public synchronized void append(ChatEntry entry) {
    entries.add(entry);
  }

  /** Apply a list of new entries received in a {@code STATE_SYNC} delta. Thread-safe. */
  public synchronized void applyDelta(List<ChatEntry> delta) {
    entries.addAll(delta);
  }

  /**
   * Replace the entire history with a snapshot received in a {@code HISTORY_SNAPSHOT}. Used by
   * replicas on startup or rejoin. Thread-safe.
   */
  public synchronized void resetFromSnapshot(List<ChatEntry> snapshot) {
    entries.clear();
    entries.addAll(snapshot);
  }

  /** Return an immutable snapshot of the current history. Thread-safe. */
  public synchronized List<ChatEntry> snapshot() {
    return List.copyOf(entries);
  }

  /** Return the number of entries currently stored. Thread-safe. */
  public synchronized int size() {
    return entries.size();
  }
}
