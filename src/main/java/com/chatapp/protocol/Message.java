package com.chatapp.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * The wire protocol. Every datagram and every TCP frame exchanged between servers and clients is a
 * {@code Message} serialized to JSON by {@link Codec}.
 *
 * <p>The JSON envelope is flat: a {@code "type"} discriminator plus the three fields common to
 * every message ({@code senderId}, {@code senderRole}, {@code ts}) and then the type-specific
 * fields (the "payload"). For example a chat message on the wire is:
 *
 * <pre>{@code
 * {"type":"CHAT","senderId":7,"senderRole":"client","ts":1716557000123,"from":"ayham","text":"hi"}
 * }</pre>
 *
 * <p>The full catalogue (who sends what, over which transport) lives in {@code docs/protocol.md}.
 * This is a sealed hierarchy, so a {@code switch} over a {@code Message} is exhaustive without a
 * default branch.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Message.DiscoveryHello.class, name = "DISCOVERY_HELLO"),
  @JsonSubTypes.Type(value = Message.DiscoveryReply.class, name = "DISCOVERY_REPLY"),
  @JsonSubTypes.Type(value = Message.Heartbeat.class, name = "HEARTBEAT"),
  @JsonSubTypes.Type(value = Message.ElectionVote.class, name = "ELECTION_VOTE"),
  @JsonSubTypes.Type(value = Message.IAmLeader.class, name = "I_AM_LEADER"),
  @JsonSubTypes.Type(value = Message.Chat.class, name = "CHAT"),
  @JsonSubTypes.Type(value = Message.StateSync.class, name = "STATE_SYNC"),
  @JsonSubTypes.Type(value = Message.HistoryRequest.class, name = "HISTORY_REQUEST"),
  @JsonSubTypes.Type(value = Message.HistorySnapshot.class, name = "HISTORY_SNAPSHOT")
})
public sealed interface Message
    permits Message.DiscoveryHello,
        Message.DiscoveryReply,
        Message.Heartbeat,
        Message.ElectionVote,
        Message.IAmLeader,
        Message.Chat,
        Message.StateSync,
        Message.HistoryRequest,
        Message.HistorySnapshot {

  /** Numeric id of the sender. A server's {@code SERVER_ID}, or a client's ephemeral id. */
  int senderId();

  /** Whether the sender is a server or a client. */
  SenderRole senderRole();

  /** Send time as epoch milliseconds, for logging and debugging (not used for ordering). */
  long ts();

  /** The two kinds of participant on the wire. Serialized lowercase, per the envelope spec. */
  enum SenderRole {
    @JsonProperty("server")
    SERVER,
    @JsonProperty("client")
    CLIENT
  }

  /** One entry in the chat history, replicated from the leader to replicas. */
  record ChatEntry(String from, String text, long ts) {}

  /** Broadcast by a starting server or client to announce itself and find the group. */
  record DiscoveryHello(int senderId, SenderRole senderRole, long ts, String host, int port)
      implements Message {}

  /**
   * Unicast reply to a {@link DiscoveryHello}: the responder's own address plus the current leader
   * id, or {@code null} if no leader is known yet.
   */
  record DiscoveryReply(
      int senderId, SenderRole senderRole, long ts, String host, int port, Integer leaderId)
      implements Message {}

  /** Liveness ping a server unicasts to each peer every {@code HEARTBEAT_INTERVAL_S}. */
  record Heartbeat(int senderId, SenderRole senderRole, long ts) implements Message {}

  /** A server's vote for itself during an election round; the highest {@code candidateId} wins. */
  record ElectionVote(int senderId, SenderRole senderRole, long ts, int candidateId)
      implements Message {}

  /** The winner of an election announces the new {@code leaderId} to servers and clients. */
  record IAmLeader(int senderId, SenderRole senderRole, long ts, int leaderId) implements Message {}

  /** A chat line: client to leader, then fanned back out by the leader to every client. */
  record Chat(int senderId, SenderRole senderRole, long ts, String from, String text)
      implements Message {}

  /** History delta the leader pushes to replicas on every accepted chat message. */
  record StateSync(int senderId, SenderRole senderRole, long ts, List<ChatEntry> messages)
      implements Message {}

  /** A replica asks the leader for a full history snapshot when it (re)joins. */
  record HistoryRequest(int senderId, SenderRole senderRole, long ts) implements Message {}

  /** The leader's full history sent in response to a {@link HistoryRequest}. */
  record HistorySnapshot(int senderId, SenderRole senderRole, long ts, List<ChatEntry> messages)
      implements Message {}
}
