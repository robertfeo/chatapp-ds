ackage com.chatapp.client;

import com.chatapp.config.Config;
import com.chatapp.discovery.Peer;
import com.chatapp.protocol.Codec;
import com.chatapp.protocol.CodecException;
import com.chatapp.protocol.Message;
import com.chatapp.protocol.Message.Chat;
import com.chatapp.protocol.Message.DiscoveryHello;
import com.chatapp.protocol.Message.DiscoveryReply;
import com.chatapp.protocol.Message.SenderRole;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal chat client.
 *
 * <p>On startup: broadcasts a {@link DiscoveryHello}, waits for {@link DiscoveryReply} responses,
 * picks the current leader, opens a TCP connection and forwards user input as {@link Chat} messages
 * while printing incoming {@link Chat} messages from the server.
 *
 * <p>Exits cleanly when stdin closes (EOF / Ctrl+C) or {@link #stop()} is called.
 */
public final class ChatClient {

  private static final Logger log = LoggerFactory.getLogger(ChatClient.class);
  static final int DISCOVERY_TIMEOUT_MS = 3_000;
  private static final int MAX_DATAGRAM = 8192;

  private final String name;
  private final int clientId;
  private final int discoveryPort;
  private final String broadcastAddr;

  private final AtomicBoolean running = new AtomicBoolean(true);

  public ChatClient(String name, int clientId, int discoveryPort, String broadcastAddr) {
    this.name = name;
    this.clientId = clientId;
    this.discoveryPort = discoveryPort;
    this.broadcastAddr = broadcastAddr;
  }

  /** Start the client. Blocks until stdin closes or {@link #stop()} is called. */
  public void start() {
    System.out.println("[chatapp] Connecting as \"" + name + "\" ...");

    try (BufferedReader stdin =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

      Peer leader = discoverLeader();
      if (leader == null) {
        System.err.println("[chatapp] No leader found. Is a server running?");
        return;
      }
      System.out.println("[chatapp] Connected to leader=" + leader.id());
      connectAndRun(leader, stdin);
    } catch (IOException e) {
      log.error("event=stdin_error error={}", e.toString());
    }
  }

  /** Stop the client gracefully. */
  public void stop() {
    running.set(false);
  }

  // -------------------------------------------------------------------------
  // Discovery
  // -------------------------------------------------------------------------

  private Peer discoverLeader() {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setBroadcast(true);
      socket.setSoTimeout(DISCOVERY_TIMEOUT_MS);

      DiscoveryHello hello =
          new DiscoveryHello(
              clientId,
              SenderRole.CLIENT,
              System.currentTimeMillis(),
              InetAddress.getLocalHost().getHostAddress(),
              0);
      byte[] payload = Codec.encode(hello);
      InetAddress dst = InetAddress.getByName(broadcastAddr);
      socket.send(new DatagramPacket(payload, payload.length, dst, discoveryPort));

      Map<Integer, DiscoveryReply> replies = new HashMap<>();
      long deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS;

      while (System.currentTimeMillis() < deadline) {
        try {
          byte[] buf = new byte[MAX_DATAGRAM];
          DatagramPacket packet = new DatagramPacket(buf, buf.length);
          socket.receive(packet);
          Message msg = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
          if (msg instanceof DiscoveryReply reply && reply.leaderId() != null) {
            replies.put(reply.senderId(), reply);
          }
        } catch (SocketTimeoutException ignored) {
          break;
        }
      }

      return selectLeader(replies);
    } catch (CodecException | IOException e) {
      log.warn("event=discovery_failed error={}", e.toString());
    }
    return null;
  }

  /**
   * Picks the leader {@link Peer} from a map of discovery replies.
   *
   * <p>Preference order:
   *
   * <ol>
   *   <li>A reply where the sender IS the leader ({@code senderId == leaderId}).
   *   <li>Any reply that reports a {@code leaderId} for which we also have a direct reply.
   * </ol>
   *
   * <p>Package-private for unit testing.
   */
  static Peer selectLeader(Map<Integer, DiscoveryReply> replies) {
    for (DiscoveryReply reply : replies.values()) {
      if (reply.leaderId() != null && reply.senderId() == reply.leaderId()) {
        return new Peer(reply.senderId(), reply.host(), reply.port());
      }
    }
    for (DiscoveryReply reply : replies.values()) {
      if (reply.leaderId() != null && replies.containsKey(reply.leaderId())) {
        DiscoveryReply leaderReply = replies.get(reply.leaderId());
        return new Peer(leaderReply.senderId(), leaderReply.host(), leaderReply.port());
      }
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // TCP session
  // -------------------------------------------------------------------------

  private void connectAndRun(Peer leader, BufferedReader stdin) {
    try (Socket socket = new Socket(leader.host(), leader.port())) {
      PrintWriter out =
          new PrintWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
              /* autoFlush= */ true);
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

      AtomicBoolean sessionActive = new AtomicBoolean(true);

      Thread.ofVirtual()
          .name("client-rx")
          .start(
              () -> {
                try {
                  String line;
                  while ((line = in.readLine()) != null) {
                    Message msg = Codec.decode(line.getBytes(StandardCharsets.UTF_8));
                    if (msg instanceof Chat chat) {
                      System.out.println("[" + chat.from() + "] " + chat.text());
                    }
                  }
                } catch (Exception ignored) {
                } finally {
                  sessionActive.set(false);
                }
              });

      while (sessionActive.get() && running.get()) {
        if (stdin.ready()) {
          String text = stdin.readLine();
          if (text == null) {
            running.set(false);
            break;
          }
          sendChat(out, text);
        } else {
          sleep(50);
        }
      }
    } catch (IOException e) {
      log.warn("event=connection_failed leader={} error={}", leader.id(), e.toString());
    }
  }

  private void sendChat(PrintWriter out, String text) {
    try {
      Chat msg = new Chat(clientId, SenderRole.CLIENT, System.currentTimeMillis(), name, text);
      out.println(new String(Codec.encode(msg), StandardCharsets.UTF_8));
    } catch (CodecException e) {
      log.warn("event=encode_failed error={}", e.toString());
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Resolve the configured discovery port from env, falling back to the default. */
  public static int resolveDiscoveryPort() {
    String raw = System.getenv("DISCOVERY_PORT");
    if (raw == null || raw.isBlank()) return Config.DEFAULT_DISCOVERY_PORT;
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return Config.DEFAULT_DISCOVERY_PORT;
    }
  }
}
