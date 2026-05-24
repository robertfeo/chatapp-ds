package com.chatapp.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * Serializes {@link Message}s to and from the JSON bytes that travel on UDP datagrams and TCP
 * frames. Stateless and thread-safe: the shared {@link ObjectMapper} is safe for concurrent use
 * once configured.
 */
public final class Codec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          // Tolerate fields a newer peer adds that this version does not know, so a rolling
          // protocol change does not break the receive loop.
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Codec() {}

  /**
   * Serialize a message to UTF-8 JSON bytes.
   *
   * @throws CodecException if the message cannot be serialized (should not happen for valid
   *     records)
   */
  public static byte[] encode(Message message) throws CodecException {
    try {
      return MAPPER.writeValueAsBytes(message);
    } catch (JsonProcessingException e) {
      throw new CodecException("failed to encode message of type " + message.getClass(), e);
    }
  }

  /**
   * Parse JSON bytes back into a {@link Message}. Never returns {@code null}.
   *
   * @throws CodecException if the payload is empty, is not valid JSON, or carries an unknown {@code
   *     type}. Callers in a receive loop catch this and continue.
   */
  public static Message decode(byte[] bytes) throws CodecException {
    if (bytes == null || bytes.length == 0) {
      throw new CodecException("cannot decode an empty payload");
    }
    try {
      return MAPPER.readValue(bytes, Message.class);
    } catch (IOException e) {
      throw new CodecException("malformed message", e);
    }
  }
}
