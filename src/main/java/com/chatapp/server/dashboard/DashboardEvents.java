package com.chatapp.server.dashboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe, bounded ring buffer of recent log lines shown in the dashboard's live event feed.
 *
 * <p>{@link DashboardLogAppender} pushes formatted lines from the logging pipeline; the dashboard
 * render loop reads the tail that fits on screen. Bounded so a long-running server never grows it
 * without limit.
 */
public final class DashboardEvents {

  private final Deque<String> lines = new ArrayDeque<>();
  private final int capacity;

  public DashboardEvents(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  /** Append one line, evicting the oldest once capacity is exceeded. */
  public synchronized void add(String line) {
    lines.addLast(line);
    while (lines.size() > capacity) {
      lines.removeFirst();
    }
  }

  /** The most recent {@code n} lines, oldest first. */
  public synchronized List<String> tail(int n) {
    if (n <= 0) {
      return List.of();
    }
    List<String> all = new ArrayList<>(lines);
    int from = Math.max(0, all.size() - n);
    return new ArrayList<>(all.subList(from, all.size()));
  }
}
