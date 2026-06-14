package com.chatapp.server.dashboard;

import com.chatapp.discovery.GroupView;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;

/**
 * A live, full-screen terminal dashboard for one server process, drawn with JLine.
 *
 * <p>Instead of a scrolling wall of log lines, it shows a fixed panel that refreshes a few times a
 * second: the server's role (leader or replica), the current leader, the live peer set, connected
 * clients and replicas, the chat-history size, the listening ports, and a feed of the most recent
 * events. State is read live from the running services through the references and suppliers passed
 * in, so the panel reflects elections, joins and chats as they happen.
 *
 * <p>JLine is used (rather than a pure-Java TUI) because it drives a native console on the Windows
 * laptops <em>and</em> the Raspberry Pi over SSH, with the platform libraries bundled in the jar.
 * Rendering is done by hand (cursor-home, then one styled line at a time via {@link
 * AttributedString#toAnsi()}) so the borders go out as plain UTF-8 box glyphs plus SGR colours,
 * never VT100 alternate-charset codes that some Windows consoles do not render.
 *
 * <p>The dashboard runs only on an interactive terminal; a piped or CI run keeps plain stdout
 * logging (see {@code ServerMain}), and if the terminal cannot be initialised the caller falls back
 * to plain logging too, so the server never depends on the dashboard to run.
 */
public final class ServerDashboard implements AutoCloseable {

  private static final long REFRESH_MS = 250;
  private static final int FEED_CAPACITY = 500;
  private static final int LABEL_WIDTH = 10;
  private static final String ESC = String.valueOf((char) 27);

  private static final AttributedStyle BORDER =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
  private static final AttributedStyle TITLE =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
  private static final AttributedStyle LABEL = AttributedStyle.DEFAULT.faint();
  private static final AttributedStyle VALUE =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
  private static final AttributedStyle PLAIN = AttributedStyle.DEFAULT;
  private static final AttributedStyle LEADER_ST =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold();
  private static final AttributedStyle REPLICA_ST =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE).bold();
  private static final AttributedStyle PENDING_ST =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();

  private final int myId;
  private final String listenHost;
  private final int listenPort;
  private final int discoveryPort;
  private final AtomicInteger currentLeaderId;
  private final GroupView groupView;
  private final IntSupplier clientCount;
  private final IntSupplier replicaCount;
  private final IntSupplier historySize;
  private final DashboardEvents events;

  private final AtomicBoolean resized = new AtomicBoolean(false);
  private final AtomicBoolean needsClear = new AtomicBoolean(true);
  private volatile boolean running;
  private long startNanos;
  private volatile Terminal terminal;
  private Thread renderThread;

  public ServerDashboard(
      int myId,
      String listenHost,
      int listenPort,
      int discoveryPort,
      AtomicInteger currentLeaderId,
      GroupView groupView,
      IntSupplier clientCount,
      IntSupplier replicaCount,
      IntSupplier historySize,
      DashboardEvents events) {
    this.myId = myId;
    this.listenHost = listenHost;
    this.listenPort = listenPort;
    this.discoveryPort = discoveryPort;
    this.currentLeaderId = currentLeaderId;
    this.groupView = groupView;
    this.clientCount = clientCount;
    this.replicaCount = replicaCount;
    this.historySize = historySize;
    this.events = events;
  }

  /** The bounded event buffer this dashboard renders; wire it to {@link DashboardLogRouting}. */
  public static DashboardEvents newEventBuffer() {
    return new DashboardEvents(FEED_CAPACITY);
  }

  /**
   * Open the terminal in screen mode and start the render loop. Throws if there is no interactive
   * terminal (the caller then falls back to plain logging).
   */
  public void start() throws IOException {
    Terminal t =
        TerminalBuilder.builder()
            .system(true)
            .name("chatapp-dashboard")
            .encoding(StandardCharsets.UTF_8)
            .build();
    if (Terminal.TYPE_DUMB.equals(t.getType()) || Terminal.TYPE_DUMB_COLOR.equals(t.getType())) {
      t.close();
      throw new IOException("no interactive terminal (type=" + t.getType() + ")");
    }
    t.enterRawMode();
    t.handle(Terminal.Signal.WINCH, s -> resized.set(true));
    t.handle(Terminal.Signal.INT, s -> stopAndExit()); // Ctrl+C delivered as a signal (Windows)
    t.puts(InfoCmp.Capability.enter_ca_mode); // alternate screen: restore scrollback on exit
    t.puts(InfoCmp.Capability.cursor_invisible);
    t.writer().write(ESC + "[?7l"); // disable autowrap so a full-width bottom row never scrolls
    t.flush();
    this.terminal = t;
    this.startNanos = System.nanoTime();
    this.running = true;
    renderThread = new Thread(this::loop, "dashboard-render");
    renderThread.setDaemon(true);
    renderThread.start();
  }

  private void loop() {
    NonBlockingReader reader = terminal.reader();
    while (running) {
      try {
        if (resized.compareAndSet(true, false)) {
          needsClear.set(true);
        }
        render();
        int c = reader.read(REFRESH_MS); // paces the loop and catches keys
        if (c == 'q' || c == 'Q' || c == 3) { // 'q' or Ctrl+C (ETX) in raw mode
          stopAndExit();
          return;
        }
        if (c == -1) { // EOF
          running = false;
          return;
        }
      } catch (IOException e) {
        running = false; // terminal went away; file logging carries on
        return;
      }
    }
  }

  private void stopAndExit() {
    running = false;
    // Orderly shutdown: the JVM shutdown hook closes services and restores the terminal.
    System.exit(0);
  }

  private void render() {
    Size size = terminal.getSize();
    int cols = Math.max(size.getColumns(), 1);
    int rows = Math.max(size.getRows(), 1);

    if (cols < 30 || rows < 10) {
      List<AttributedString> small = new ArrayList<>();
      small.add(new AttributedString("chatapp server " + myId));
      small.add(new AttributedString("(enlarge the terminal for the dashboard)"));
      while (small.size() < rows) {
        small.add(AttributedString.EMPTY);
      }
      paint(clampAll(small.subList(0, rows), cols));
      return;
    }

    boolean leader = currentLeaderId.get() == myId;
    List<AttributedString> lines = new ArrayList<>(rows);
    lines.add(topBorder(cols));
    lines.add(blankRow(cols));
    if (leader) {
      lines.add(fieldRow(cols, "ROLE", "● LEADER", LEADER_ST));
    } else if (currentLeaderId.get() <= 0) {
      lines.add(fieldRow(cols, "ROLE", "○ electing…", PENDING_ST));
    } else {
      lines.add(fieldRow(cols, "ROLE", "○ replica", REPLICA_ST));
    }
    lines.add(fieldRow(cols, "LEADER", leaderText(leader), VALUE));
    lines.add(fieldRow(cols, "PEERS", peersText(), VALUE));
    lines.add(fieldRow(cols, "CLIENTS", Integer.toString(clientCount.getAsInt()), VALUE));
    lines.add(fieldRow(cols, "REPLICAS", Integer.toString(replicaCount.getAsInt()), VALUE));
    lines.add(fieldRow(cols, "HISTORY", historySize.getAsInt() + " messages", VALUE));
    lines.add(
        fieldRow(cols, "LISTEN", listenHost + ":" + listenPort + "  TCP chat / state sync", PLAIN));
    lines.add(
        fieldRow(
            cols,
            "DISCOVERY",
            ":" + discoveryPort + "  UDP broadcast (discovery · heartbeat · election)",
            PLAIN));
    lines.add(blankRow(cols));
    lines.add(divider(cols, " live events "));

    int feedRows = rows - 1 - lines.size(); // leave the bottom border
    if (feedRows > 0) {
      List<String> feed = events.tail(feedRows);
      for (String fl : feed) {
        lines.add(feedRow(cols, fl));
      }
      for (int i = feed.size(); i < feedRows; i++) {
        lines.add(blankRow(cols));
      }
    }
    lines.add(bottomBorder(cols));

    while (lines.size() < rows) {
      lines.add(blankRow(cols));
    }
    if (lines.size() > rows) {
      lines = new ArrayList<>(lines.subList(0, rows));
    }
    paint(clampAll(lines, cols));
  }

  /** Repaint the whole screen from the top, one styled line at a time, flicker-free. */
  private void paint(List<AttributedString> lines) {
    StringBuilder sb = new StringBuilder(lines.size() * 96);
    if (needsClear.compareAndSet(true, false)) {
      sb.append(ESC).append("[2J"); // clear once on first frame / after a resize
    }
    sb.append(ESC).append("[H"); // cursor home
    for (int i = 0; i < lines.size(); i++) {
      sb.append(lines.get(i).toAnsi()); // UTF-8 glyphs + SGR colours, no VT100 alt-charset
      sb.append(ESC).append("[K"); // clear to end of line
      if (i < lines.size() - 1) {
        sb.append("\r\n");
      }
    }
    PrintWriter w = terminal.writer();
    w.write(sb.toString());
    w.flush();
  }

  private AttributedString topBorder(int cols) {
    String title = " chatapp · server " + myId + " ";
    String up = " up " + uptime() + " ";
    AttributedStringBuilder b = new AttributedStringBuilder();
    b.style(BORDER).append("┌─");
    b.style(TITLE).append(title);
    b.style(BORDER);
    fill(b, '─', cols - 1 - up.length());
    b.append(up);
    fill(b, '─', cols - 1);
    b.append('┐');
    return b.toAttributedString();
  }

  private AttributedString divider(int cols, String label) {
    AttributedStringBuilder b = new AttributedStringBuilder();
    b.style(BORDER).append("├─");
    b.style(TITLE).append(label);
    b.style(BORDER);
    fill(b, '─', cols - 1);
    b.append('┤');
    return b.toAttributedString();
  }

  private AttributedString bottomBorder(int cols) {
    AttributedStringBuilder b = new AttributedStringBuilder();
    b.style(BORDER).append("└─ q / Ctrl+C: stop ");
    fill(b, '─', cols - 1);
    b.append('┘');
    return b.toAttributedString();
  }

  private AttributedString fieldRow(int cols, String label, String value, AttributedStyle vStyle) {
    return contentRow(
        cols,
        b -> {
          b.style(LABEL).append(padRight(label, LABEL_WIDTH)).append(' ');
          b.style(vStyle).append(clip(value, cols - 1 - b.length()));
        });
  }

  private AttributedString feedRow(int cols, String line) {
    return contentRow(
        cols, b -> b.style(eventStyle(line)).append(clip(line, cols - 1 - b.length())));
  }

  private AttributedString blankRow(int cols) {
    return contentRow(cols, b -> {});
  }

  /** Wrap content between cyan borders, padded to the full width. */
  private AttributedString contentRow(int cols, Consumer<AttributedStringBuilder> body) {
    AttributedStringBuilder b = new AttributedStringBuilder();
    b.style(BORDER).append("│ ");
    body.accept(b);
    b.style(PLAIN);
    fill(b, ' ', cols - 1);
    b.style(BORDER).append('│');
    return b.toAttributedString();
  }

  private String leaderText(boolean leader) {
    int id = currentLeaderId.get();
    if (id <= 0) {
      return "pending (no leader yet)";
    }
    return leader ? id + "  (this node)" : Integer.toString(id);
  }

  private String peersText() {
    StringJoiner ids = new StringJoiner(", ");
    for (int id : groupView.ids()) {
      ids.add(Integer.toString(id));
    }
    return groupView.size() + "        " + ids;
  }

  private static AttributedStyle eventStyle(String line) {
    if (line.contains("leader_elected")
        || line.contains("leader_accepted")
        || line.contains("event=ready")) {
      return AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    }
    if (line.contains("_failed")
        || line.contains("peer_dead")
        || line.contains("rejected")
        || line.contains("_lost")
        || line.contains("shutdown")) {
      return AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    }
    if (line.contains("election")) {
      return AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
    }
    if (line.contains("client_connected")
        || line.contains("chat_accepted")
        || line.contains("replica_connected")
        || line.contains("peer_discovered")) {
      return AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    }
    return AttributedStyle.DEFAULT;
  }

  private String uptime() {
    long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
    return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
  }

  private static void fill(AttributedStringBuilder b, char c, int targetLen) {
    for (int i = b.length(); i < targetLen; i++) {
      b.append(c);
    }
  }

  private static String padRight(String s, int width) {
    if (s.length() >= width) {
      return s.substring(0, width);
    }
    return s + " ".repeat(width - s.length());
  }

  private static String clip(String s, int max) {
    if (max <= 0) {
      return "";
    }
    return s.length() > max ? s.substring(0, max) : s;
  }

  /** Truncate every line to the terminal width so nothing wraps onto the next row. */
  private static List<AttributedString> clampAll(List<AttributedString> lines, int cols) {
    List<AttributedString> out = new ArrayList<>(lines.size());
    for (AttributedString line : lines) {
      out.add(line.columnLength() > cols ? line.columnSubSequence(0, cols) : line);
    }
    return out;
  }

  @Override
  public void close() {
    running = false;
    Terminal t = terminal;
    terminal = null;
    if (t != null) {
      try {
        t.writer().write(ESC + "[?7h"); // restore autowrap
        t.puts(InfoCmp.Capability.cursor_visible);
        t.puts(InfoCmp.Capability.exit_ca_mode); // back to the normal screen + scrollback
        t.flush();
        t.close(); // restores the original (cooked) terminal attributes
      } catch (IOException ignored) {
        // best-effort restore on shutdown
      }
    }
  }
}
