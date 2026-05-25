package com.chatapp.discovery;

import com.chatapp.protocol.Message;
import java.io.IOException;
import java.net.SocketAddress;

/** Sends a {@link Message} as a UDP datagram to the given destination. */
@FunctionalInterface
public interface UdpSender {
  void send(Message message, SocketAddress destination) throws IOException;
}
