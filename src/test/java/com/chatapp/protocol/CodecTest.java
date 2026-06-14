package com.chatapp.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chatapp.protocol.Message.Answer;
import com.chatapp.protocol.Message.Chat;
import com.chatapp.protocol.Message.ChatEntry;
import com.chatapp.protocol.Message.DiscoveryHello;
import com.chatapp.protocol.Message.DiscoveryReply;
import com.chatapp.protocol.Message.ElectionInquiry;
import com.chatapp.protocol.Message.Heartbeat;
import com.chatapp.protocol.Message.HistoryRequest;
import com.chatapp.protocol.Message.HistorySnapshot;
import com.chatapp.protocol.Message.IAmLeader;
import com.chatapp.protocol.Message.SenderRole;
import com.chatapp.protocol.Message.StateSync;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CodecTest {

  /** One representative instance of every message type. */
  static Stream<Message> everyMessageType() {
    return Stream.of(
        new DiscoveryHello(3, SenderRole.SERVER, 1000L, "192.168.0.3", 5003),
        new DiscoveryReply(2, SenderRole.SERVER, 1001L, "192.168.0.2", 5002, 3),
        new DiscoveryReply(2, SenderRole.SERVER, 1001L, "192.168.0.2", 5002, null),
        new Heartbeat(3, SenderRole.SERVER, 1002L, 2, 1),
        new ElectionInquiry(1, SenderRole.SERVER, 1003L),
        new Answer(2, SenderRole.SERVER, 1003L),
        new IAmLeader(3, SenderRole.SERVER, 1004L, 3),
        new Chat(7, SenderRole.CLIENT, 1005L, "ayham", "hello there"),
        new StateSync(
            3,
            SenderRole.SERVER,
            1006L,
            List.of(new ChatEntry("ayham", "hi", 1005L), new ChatEntry("samet", "yo", 1006L))),
        new HistoryRequest(2, SenderRole.SERVER, 1007L),
        new HistorySnapshot(
            3, SenderRole.SERVER, 1008L, List.of(new ChatEntry("rob", "hey", 999L))));
  }

  @ParameterizedTest
  @MethodSource("everyMessageType")
  void roundTripsEveryType(Message original) throws CodecException {
    Message decoded = Codec.decode(Codec.encode(original));
    assertEquals(original, decoded, "decode(encode(m)) must equal m");
    assertEquals(original.getClass(), decoded.getClass(), "concrete type must be preserved");
  }

  @ParameterizedTest
  @MethodSource("everyMessageType")
  void encodingIsStable(Message original) throws CodecException {
    // Encoding the value decoded from a previous encoding yields identical bytes.
    byte[] once = Codec.encode(original);
    byte[] twice = Codec.encode(Codec.decode(once));
    assertArrayEquals(once, twice);
  }

  @Test
  void encodesTheTypeDiscriminator() throws CodecException {
    String json =
        new String(
            Codec.encode(new Heartbeat(3, SenderRole.SERVER, 5L, 0, 0)), StandardCharsets.UTF_8);
    org.junit.jupiter.api.Assertions.assertTrue(
        json.contains("\"type\":\"HEARTBEAT\""), "envelope must carry the type tag: " + json);
  }

  @Test
  void decodeRejectsEmptyPayload() {
    assertThrows(CodecException.class, () -> Codec.decode(new byte[0]));
    assertThrows(CodecException.class, () -> Codec.decode(null));
  }

  @Test
  void decodeRejectsGarbage() {
    assertThrows(
        CodecException.class,
        () -> Codec.decode("this is not json".getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        CodecException.class, () -> Codec.decode("{ broken".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void decodeRejectsUnknownType() {
    byte[] unknown =
        "{\"type\":\"NOT_A_REAL_TYPE\",\"senderId\":1,\"senderRole\":\"server\",\"ts\":1}"
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(CodecException.class, () -> Codec.decode(unknown));
  }

  @Test
  void decodeToleratesUnknownExtraFields() throws CodecException {
    // Forward compatibility: an extra field added by a newer peer must not break decoding.
    byte[] withExtra =
        ("{\"type\":\"HEARTBEAT\",\"senderId\":3,\"senderRole\":\"server\",\"ts\":42,"
                + "\"futureField\":\"ignored\"}")
            .getBytes(StandardCharsets.UTF_8);
    Message decoded = Codec.decode(withExtra);
    assertNotNull(decoded);
    // The new connection-count fields are absent here (an older peer's heartbeat); they must
    // default to 0 rather than fail, so a mixed-version cluster still decodes heartbeats.
    assertEquals(new Heartbeat(3, SenderRole.SERVER, 42L, 0, 0), decoded);
  }
}
