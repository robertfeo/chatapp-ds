package com.chatapp.server;

import com.chatapp.config.ServerConfig;
import com.chatapp.protocol.Message;
import com.chatapp.protocol.Message.IAmLeader;
import com.chatapp.protocol.Message.SenderRole;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts TCP connections from clients on {@code config.listenPort()}.
 *
 * <p>Each connection is served by a virtual thread. On leader change, the server pushes an {@link
 * IAmLeader} notification to every currently-connected client so they can re-route without waiting
 * for their TCP connection to drop.
 */
public final class TcpServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TcpServer.class);

  private final int myId;
  private final int listenPort;
  private final Consumer<Message> incomingMessageHandler;

  private volatile ServerSocket serverSocket;
  private volatile boolean running;
  private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();

  /**
   * @param incomingMessageHandler called for every message received from any connected client; used
   *     by the chat layer (Issue #12) to dispatch incoming messages.
   */
  public TcpServer(ServerConfig config, Consumer<Message> incomingMessageHandler) {
    this.myId = config.serverId();
    this.listenPort = config.listenPort();
    this.incomingMessageHandler = incomingMessageHandler;
  }

  /** Bind the server socket and start the accept loop in a virtual thread. */
  public void start() throws IOException {
    serverSocket = new ServerSocket(listenPort);
    running = true;
    Thread.ofVirtual().name("tcp-acceptor-" + myId).start(this::acceptLoop);
    log.info("event=tcp_server_started myId={} port={}", myId, listenPort);
  }

  /**
   * Push an {@link IAmLeader} notification to every connected client. Called both when this server
   * wins an election and when it receives an {@link IAmLeader} from a peer (so clients connected to
   * any server are kept in sync).
   */
  public void notifyLeaderChange(int newLeaderId) {
    IAmLeader msg = new IAmLeader(myId, SenderRole.SERVER, System.currentTimeMillis(), newLeaderId);
    int count = 0;
    for (ClientSession s : sessions) {
      if (s.isOpen()) {
        s.send(msg);
        count++;
      }
    }
    if (count > 0) {
      log.info(
          "event=clients_notified myId={} newLeaderId={} clientCount={}", myId, newLeaderId, count);
    }
  }

  private void acceptLoop() {
    while (running) {
      Socket socket;
      try {
        socket = serverSocket.accept();
      } catch (SocketException e) {
        return; // closed during shutdown
      } catch (IOException e) {
        if (running) {
          log.warn("event=accept_failed myId={} error={}", myId, e.toString());
        }
        continue;
      }

      ClientSession session;
      try {
        session = new ClientSession(socket);
      } catch (IOException e) {
        log.warn("event=session_init_failed myId={} error={}", myId, e.toString());
        try {
          socket.close();
        } catch (IOException ignored) {
        }
        continue;
      }

      sessions.add(session);
      log.info(
          "event=client_connected myId={} remote={} totalClients={}",
          myId,
          session.remoteAddress(),
          sessions.size());
      Thread.ofVirtual()
          .name("tcp-client-" + session.remoteAddress())
          .start(() -> serveClient(session));
    }
  }

  private void serveClient(ClientSession session) {
    try {
      while (session.isOpen()) {
        Message msg = session.receive();
        if (msg == null) break; // client disconnected
        incomingMessageHandler.accept(msg);
      }
    } catch (Exception e) {
      if (session.isOpen()) {
        log.debug(
            "event=client_read_error myId={} remote={} error={}",
            myId,
            session.remoteAddress(),
            e.toString());
      }
    } finally {
      sessions.remove(session);
      session.close();
      log.info(
          "event=client_disconnected myId={} remote={} totalClients={}",
          myId,
          session.remoteAddress(),
          sessions.size());
    }
  }

  @Override
  public void close() {
    running = false;
    for (ClientSession s : sessions) {
      s.close();
    }
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (IOException ignored) {
      }
    }
  }
}
