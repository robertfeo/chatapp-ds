package com.chatapp.protocol;

/**
 * Thrown when a {@link Message} cannot be encoded, or when a received payload cannot be decoded
 * (malformed JSON, unknown {@code type}, or an empty datagram).
 *
 * <p>It is checked on purpose: a receive loop must catch it and carry on rather than die on one bad
 * packet from the network.
 */
public class CodecException extends Exception {

  public CodecException(String message) {
    super(message);
  }

  public CodecException(String message, Throwable cause) {
    super(message, cause);
  }
}
