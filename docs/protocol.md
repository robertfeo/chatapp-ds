# Wire protocol

Every datagram and every TCP frame in chatapp-ds is a single JSON object: one
**`Message`**. The Java side is the sealed interface
[`com.chatapp.protocol.Message`](../src/main/java/com/chatapp/protocol/Message.java);
serialization is [`com.chatapp.protocol.Codec`](../src/main/java/com/chatapp/protocol/Codec.java).

## Envelope

The envelope is flat. A `type` discriminator selects the message; three fields
are common to every type; the remaining fields are that type's payload.

| Field | Type | Meaning |
|---|---|---|
| `type` | string | Discriminator, one of the type tags below. |
| `senderId` | int | Server `SERVER_ID`, or a client's ephemeral id. |
| `senderRole` | `"server"` \| `"client"` | Who sent it. |
| `ts` | long | Send time, epoch milliseconds. For logging only, **not** for ordering. |

Example (a chat line):

```json
{"type":"CHAT","senderId":7,"senderRole":"client","ts":1716557000123,"from":"ayham","text":"hi"}
```

## Message catalogue

Transport column: **UDP-B** = UDP broadcast, **UDP-U** = UDP unicast, **TCP**.

| `type` | Sender | Receiver | Transport | Payload fields |
|---|---|---|---|---|
| `DISCOVERY_HELLO` | server or client (joining) | all servers | UDP-B | `host` (string), `port` (int) |
| `DISCOVERY_REPLY` | server | the joiner | UDP-U | `host` (string), `port` (int), `leaderId` (int, nullable) |
| `HEARTBEAT` | server | each peer server | UDP-U | none |
| `ELECTION_VOTE` | server | all servers | UDP-B | `candidateId` (int) |
| `I_AM_LEADER` | new leader | all servers and clients | UDP-B | `leaderId` (int) |
| `CHAT` | client then leader | leader, then all clients | TCP | `from` (string), `text` (string) |
| `STATE_SYNC` | leader | each replica | TCP | `messages` (list of `ChatEntry`) |
| `HISTORY_REQUEST` | replica (re)joining | leader | TCP | none |
| `HISTORY_SNAPSHOT` | leader | the requesting replica | TCP | `messages` (list of `ChatEntry`) |

### `ChatEntry`

A single line of chat history, used inside `STATE_SYNC` and `HISTORY_SNAPSHOT`:

| Field | Type | Meaning |
|---|---|---|
| `from` | string | Display name of the author. |
| `text` | string | The message text. |
| `ts` | long | When the leader accepted it, epoch milliseconds. |

## Codec contract

- `Codec.encode(Message)` returns UTF-8 JSON `byte[]`.
- `Codec.decode(byte[])` returns a `Message`, never `null`.
- `decode` throws the checked `CodecException` on an empty payload, invalid
  JSON, or an unknown `type`. A receive loop catches it and continues, so one
  bad packet never kills the loop.
- Unknown extra fields are ignored on decode, so a newer peer can add a field
  without breaking older peers.

## Notes

- The envelope is intentionally minimal. `ts` is for human-readable logs and
  debugging; ordering is not derived from it (no vector clocks, by design).
- The hierarchy is sealed: a `switch` over a `Message` is exhaustive, so adding
  a type is a compile error everywhere it must be handled.
