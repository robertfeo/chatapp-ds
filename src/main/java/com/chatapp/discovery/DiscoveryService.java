package com.chatapp.discovery;

import com.chatapp.config.Config;
import com.chatapp.config.ServerConfig;
import com.chatapp.protocol.Codec;
import com.chatapp.protocol.CodecException;
import com.chatapp.protocol.Message;
import com.chatapp.protocol.Message.DiscoveryHello;
import com.chatapp.protocol.Message.DiscoveryReply;
import com.chatapp.protocol.Message.SenderRole;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UDP-broadcast discovery, server side. On start it broadcasts a {@link DiscoveryHello} so peers
 * learn about it, listens for peers' hellos to build the local {@link GroupView}, and replies by
 * unicast with a {@link DiscoveryReply} carrying its own address and the current leader id.
 *
 * <p>Re-announcement repeats every {@link Config#DISCOVERY_REANNOUNCE_S} seconds so late joiners
 * are eventually included. On localhost, several servers share the discovery port with {@code
 * SO_REUSEPORT}; on a real LAN each host has its own address.
 */
public final class DiscoveryService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);
  private static final int MAX_DATAGRAM = 8192;

  private final ServerConfig config;
  private final GroupView groupView;
  private final Supplier<Integer> currentLeaderId;
  private final Consumer<Message> extraHandler;
  private final String advertisedHost;

  private volatile DatagramSocket socket;
  private volatile boolean running;
  private Thread listener;
  private ScheduledExecutorService announcer;

  public DiscoveryService(
      ServerConfig config, GroupView groupView, Supplier<Integer> currentLeaderId) {
    this(config, groupView, currentLeaderId, msg -> {});
  }

  public DiscoveryService(
      ServerConfig config,
      GroupView groupView,
      Supplier<Integer> currentLeaderId,
      Consumer<Message> extraHandler) {
    this.config = config;
    this.groupView = groupView;
    this.currentLeaderId = currentLeaderId;
    this.extraHandler = extraHandler;
    this.advertisedHost = advertisedHost(config);
  }

  /** Bind the socket, start listening, and begin announcing. Idempotent guard: call once. */
  public void start() throws SocketException {
    DatagramSocket s = new DatagramSocket(null);
    s.setReuseAddress(true);
    // On Linux, several processes sharing one UDP port (the localhost dev setup) need
    // SO_REUSEPORT, not just SO_REUSEADDR. It is not needed on a real LAN and is unsupported on
    // some platforms, so set it best-effort.
    try {
      s.setOption(StandardSocketOptions.SO_REUSEPORT, true);
    } catch (UnsupportedOperationException | IOException ignored) {
      // fine: distinct hosts on the LAN do not share a port
    }
    s.setBroadcast(true);
    try {
      s.bind(new InetSocketAddress((InetAddress) null, config.discoveryPort()));
    } catch (SocketException e) {
      s.close();
      throw e;
    }
    this.socket = s;
    this.running = true;

    listener =
        Thread.ofVirtual().name("discovery-listener-" + config.serverId()).start(this::receiveLoop);

    announcer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "discovery-announcer-" + config.serverId());
              t.setDaemon(true);
              return t;
            });
    announcer.scheduleAtFixedRate(
        this::announceQuietly, 0, Config.DISCOVERY_REANNOUNCE_S, TimeUnit.SECONDS);
  }

  /**
   * Broadcast a {@link DiscoveryHello}. Sent to every live interface's own broadcast address
   * <em>and</em> the configured fallback (the limited broadcast {@code 255.255.255.255} by
   * default), so it leaves the real LAN interface even on a host crowded with VPN and virtual
   * adapters where a single broadcast target would be sent out the wrong one.
   */
  public void announce() throws IOException {
    DiscoveryHello hello =
        new DiscoveryHello(
            config.serverId(),
            SenderRole.SERVER,
            System.currentTimeMillis(),
            advertisedHost,
            config.listenPort());
    IOException last = null;
    for (InetSocketAddress target : broadcastTargets()) {
      try {
        send(hello, target);
      } catch (IOException e) {
        last = e; // a dead virtual interface must not stop the others
      }
    }
    log.info(
        "event=group_view serverId={} size={} ids={}",
        config.serverId(),
        groupView.size(),
        groupView.ids());
    if (last != null && groupView.size() == 1) {
      throw last; // nothing got out and we are still alone; surface it to announceQuietly
    }
  }

  /** The configured broadcast address plus each up, non-loopback interface's own broadcast. */
  private List<InetSocketAddress> broadcastTargets() {
    Set<InetSocketAddress> targets = new LinkedHashSet<>();
    int port = config.discoveryPort();
    try {
      targets.add(new InetSocketAddress(InetAddress.getByName(config.broadcastAddr()), port));
    } catch (IOException ignored) {
      // a bad configured value must not stop interface-derived targets
    }
    try {
      for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nif.isUp() || nif.isLoopback()) {
          continue;
        }
        for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
          InetAddress b = ia.getBroadcast();
          if (b != null) {
            targets.add(new InetSocketAddress(b, port));
          }
        }
      }
    } catch (SocketException ignored) {
      // fall back to just the configured target
    }
    return new ArrayList<>(targets);
  }

  private void announceQuietly() {
    try {
      announce();
    } catch (IOException e) {
      log.warn("event=announce_failed serverId={} error={}", config.serverId(), e.toString());
    }
  }

  private void receiveLoop() {
    byte[] buffer = new byte[MAX_DATAGRAM];
    while (running) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
      } catch (SocketException e) {
        return; // socket closed during shutdown
      } catch (IOException e) {
        if (running) {
          log.warn("event=receive_failed serverId={} error={}", config.serverId(), e.toString());
        }
        continue;
      }
      handle(packet);
    }
  }

  private void handle(DatagramPacket packet) {
    Message message;
    try {
      message = Codec.decode(java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
    } catch (CodecException e) {
      // One bad datagram must not kill the loop.
      log.debug("event=decode_failed serverId={} from={}", config.serverId(), packet.getAddress());
      return;
    }

    switch (message) {
      case DiscoveryHello hello -> onHello(hello, packet);
      case DiscoveryReply reply -> onReply(reply, packet);
      default -> {
        // Votes and leader announcements are broadcast, so our own come back to us; drop them.
        if (message.senderId() != config.serverId()) {
          extraHandler.accept(message);
        }
      }
    }
  }

  private void onHello(DiscoveryHello hello, DatagramPacket packet) {
    if (hello.senderId() == config.serverId()) {
      return; // our own broadcast looped back
    }
    learn(hello.senderId(), packet.getAddress().getHostAddress(), hello.port());

    // Reply directly to the sender so it learns about us and our current leader id.
    DiscoveryReply reply =
        new DiscoveryReply(
            config.serverId(),
            SenderRole.SERVER,
            System.currentTimeMillis(),
            advertisedHost,
            config.listenPort(),
            currentLeaderId.get());
    try {
      send(reply, packet.getSocketAddress());
    } catch (IOException e) {
      log.warn("event=reply_failed serverId={} error={}", config.serverId(), e.toString());
    }
  }

  private void onReply(DiscoveryReply reply, DatagramPacket packet) {
    if (reply.senderId() == config.serverId()) {
      return;
    }
    learn(reply.senderId(), packet.getAddress().getHostAddress(), reply.port());
  }

  private void learn(int id, String host, int port) {
    boolean isNew = groupView.upsert(new Peer(id, host, port));
    if (isNew) {
      log.info(
          "event=peer_discovered serverId={} peer_id={} host={} port={}",
          config.serverId(),
          id,
          host,
          port);
    }
  }

  public void send(Message message, SocketAddress destination) throws IOException {
    try {
      byte[] bytes = Codec.encode(message);
      socket.send(new DatagramPacket(bytes, bytes.length, destination));
    } catch (CodecException e) {
      throw new IOException("failed to encode outgoing discovery message", e);
    }
  }

  @Override
  public void close() {
    running = false;
    if (announcer != null) {
      announcer.shutdownNow();
    }
    if (socket != null) {
      socket.close(); // unblocks receive()
    }
    if (listener != null) {
      listener.interrupt();
    }
  }

  /** Best-effort routable address to advertise; receivers prefer the datagram source anyway. */
  private static String advertisedHost(ServerConfig config) {
    String configured = config.listenHost();
    if (configured != null && !configured.isBlank() && !configured.equals("0.0.0.0")) {
      return configured;
    }
    InetAddress lan = primaryLanAddress();
    return lan != null ? lan.getHostAddress() : "127.0.0.1";
  }

  /** The host's primary LAN IPv4 (the interface that owns the default route), or {@code null}. */
  private static InetAddress primaryLanAddress() {
    // The kernel picks the default-route interface's source address; no packet is sent.
    try (DatagramSocket probe = new DatagramSocket()) {
      probe.connect(InetAddress.getByName("8.8.8.8"), 53);
      InetAddress local = probe.getLocalAddress();
      if (local instanceof Inet4Address
          && !local.isAnyLocalAddress()
          && !local.isLoopbackAddress()) {
        return local;
      }
    } catch (IOException ignored) {
      // fall through to interface enumeration
    }
    try {
      for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nif.isUp() || nif.isLoopback()) {
          continue;
        }
        for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
          if (addr instanceof Inet4Address
              && !addr.isLoopbackAddress()
              && !addr.isLinkLocalAddress()) {
            return addr;
          }
        }
      }
    } catch (SocketException ignored) {
      // no usable interface
    }
    return null;
  }
}
