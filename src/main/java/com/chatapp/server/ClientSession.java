package com.chatapp.server;

import com.chatapp.protocol.Codec;
import com.chatapp.protocol.CodecException;
import com.chatapp.protocol.Message;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps one accepted TCP connection. Messages are newline-delimited JSON (one JSON object per
 * line), which keeps the framing simple and human-readable in logs.
 */
public final class ClientSession implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

  private final Socket socket;
  private final BufferedReader reader;
  private final PrintWriter writer;
  private volatile boolean open = true;

  public ClientSession(Socket socket) throws IOException {
    this.socket = socket;
    this.reader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    this.writer =
        new PrintWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
            /* autoFlush= */ true);
  }

  /** Send a message to this client. Silently closes the session on any I/O error. */
  public void send(Message msg) {
    if (!open) return;
    try {
      String line = new String(Codec.encode(msg), StandardCharsets.UTF_8);
      writer.println(line);
      if (writer.checkError()) {
        close();
      }
    } catch (CodecException e) {
      log.warn("event=send_encode_failed remote={}", socket.getRemoteSocketAddress());
      close();
    }
  }

  /** Read one newline-terminated JSON line, decode it, and return it. Returns null on EOF. */
  public Message receive() throws IOException, CodecException {
    String line = reader.readLine();
    if (line == null) return null;
    return Codec.decode(line.getBytes(StandardCharsets.UTF_8));
  }

  public boolean isOpen() {
    return open && !socket.isClosed();
  }

  public String remoteAddress() {
    return socket.getRemoteSocketAddress().toString();
  }

  @Override
  public void close() {
    open = false;
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
