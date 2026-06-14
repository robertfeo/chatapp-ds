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
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts TCP connections from clients <em>and</em> replicas on {@code config.listenPort()}.
 *
 * <p>Sessions are classified on the first received message: {@link SenderRole#SERVER} messages come
 * from replicas (server-to-server sync); {@link SenderRole#CLIENT} messages come from chat clients.
 * Each group is tracked in a separate set so the caller can broadcast to one or both groups
 * independently.
 */
public final class TcpServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TcpServer.class);

  private final int myId;
  private final int listenPort;

  /** Called for every message received, together with the session it arrived on. */
  private final BiConsumer<Message, ClientSession> messageHandler;

  private volatile ServerSocket serverSocket;
  private volatile boolean running;

  private final Set<ClientSession> clientSessions = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> replicaSessions = ConcurrentHashMap.newKeySet();

  public TcpServer(ServerConfig config, BiConsumer<Message, ClientSession> messageHandler) {
    this.myId = config.serverId();
    this.listenPort = config.listenPort();
    this.messageHandler = messageHandler;
  }

  /** Bind the server socket and start the accept loop in a virtual thread. */
  public void start() throws IOException {
    serverSocket = new ServerSocket(listenPort);
    running = true;
    Thread.ofVirtual().name("tcp-acceptor-" + myId).start(this::acceptLoop);
    log.info("event=tcp_server_started myId={} port={}", myId, listenPort);
  }

  /** Send an {@link IAmLeader} notification to all connected clients and replicas. */
  public void notifyLeaderChange(int newLeaderId) {
    IAmLeader msg = new IAmLeader(myId, SenderRole.SERVER, System.currentTimeMillis(), newLeaderId);
    broadcastToClients(msg);
    broadcastToReplicas(msg);
    log.info(
        "event=leader_change_broadcast myId={} newLeaderId={} clients={} replicas={}",
        myId,
        newLeaderId,
        clientSessions.size(),
        replicaSessions.size());
  }

  /** Send {@code msg} to every connected chat client. */
  public void broadcastToClients(Message msg) {
    for (ClientSession s : clientSessions) {
      if (s.isOpen()) s.send(msg);
    }
  }

  /** Send {@code msg} to every connected replica. */
  public void broadcastToReplicas(Message msg) {
    for (ClientSession s : replicaSessions) {
      if (s.isOpen()) s.send(msg);
    }
  }

  /** Number of chat clients currently connected. Read by the live dashboard. */
  public int clientCount() {
    return clientSessions.size();
  }

  /** Number of replica servers currently connected. Read by the live dashboard. */
  public int replicaCount() {
    return replicaSessions.size();
  }

  private void acceptLoop() {
    while (running) {
      Socket socket;
      try {
        socket = serverSocket.accept();
      } catch (SocketException e) {
        return;
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

      log.info("event=connection_accepted myId={} remote={}", myId, session.remoteAddress());
      Thread.ofVirtual()
          .name("tcp-session-" + session.remoteAddress())
          .start(() -> serveSession(session));
    }
  }

  private void serveSession(ClientSession session) {
    boolean classified = false;
    try {
      while (session.isOpen()) {
        Message msg = session.receive();
        if (msg == null) break;

        if (!classified) {
          classified = true;
          if (msg.senderRole() == SenderRole.SERVER) {
            replicaSessions.add(session);
            log.info(
                "event=replica_connected myId={} remote={} totalReplicas={}",
                myId,
                session.remoteAddress(),
                replicaSessions.size());
          } else {
            clientSessions.add(session);
            log.info(
                "event=client_connected myId={} remote={} totalClients={}",
                myId,
                session.remoteAddress(),
                clientSessions.size());
          }
        }

        messageHandler.accept(msg, session);
      }
    } catch (Exception e) {
      if (session.isOpen()) {
        log.debug(
            "event=session_read_error myId={} remote={} error={}",
            myId,
            session.remoteAddress(),
            e.toString());
      }
    } finally {
      clientSessions.remove(session);
      replicaSessions.remove(session);
      session.close();
      log.info("event=session_closed myId={} remote={}", myId, session.remoteAddress());
    }
  }

  @Override
  public void close() {
    running = false;
    for (ClientSession s : clientSessions) s.close();
    for (ClientSession s : replicaSessions) s.close();
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (IOException ignored) {
      }
    }
  }
}
