# Manual Test Plan v1

The checklist the team follows during every dry run and during the live demo. Each scenario lists
the steps, the **expected log lines** to watch for, and the pass criterion. It is written so that
anyone on the team (not only the author) can run it.

> On an interactive terminal each server shows a **live dashboard** (role, current leader, peer set,
> clients, replicas, history, and a feed of the events below) rather than scrolling logs. Read the
> expected `event=...` lines off the panel's feed; the full log is also in `logs/server-<id>.log`.
> Stop a server with `q` or `Ctrl+C` in its panel.

## Setup (once per host)

- Java 21 on every host (`java -version`).
- All hosts on the **same LAN / hotspot** (not eduroam, not an isolating "guest" Wi-Fi).
- Windows servers run on **native Windows, not WSL** (WSL2's NAT keeps broadcast off the LAN).
- No configuration is needed: the server picks a **random id**, detects its LAN address, uses the
  default ports (discovery UDP **45678**, chat TCP **6000**). Start with the launchers or the jar:
  - Linux/Pi: `./start.sh server` (or `java -jar chatapp.jar server`)
  - Windows: `.\start.bat server` (or `java -jar chatapp.jar server`)
  - Client: same launcher with `client` instead of `server`
- See `docs/demo_runbook.md` for the full bring-up and troubleshooting.

> Ids are random integers, so they differ on every run. In the expected-log snippets below they are
> shown as `<idA>` (the highest, i.e. the leader), `<idB>`, `<idC>`. The leader is always the
> **highest** live id.

---

## Core scenarios (issue #18)

### 1. Happy path - 3 servers, 2 clients, messages arrive on both

**Steps**
1. Start three servers, one per host. Wait until all three are up.
2. Start two clients (any hosts). Type one line in **each** client first (a client registers with
   the leader on its first sent message).
3. Type a message in client 1.

**Expected logs (servers)**
```
event=server_id_auto serverId=<id> source=random
event=startup ... discoveryPort=45678 ...
event=peer_discovered ... peer_id=<...>
event=group_view ... size=3 ids=[<idC>, <idB>, <idA>]
event=leader_elected myId=<idA>            # on the highest-id host
event=leader_accepted ... leaderId=<idA>    # on the other two
event=client_connected ... totalClients=2
event=chat_accepted ... from=<client name>
```
**Pass:** all three servers reach `size=3`, exactly one logs `leader_elected`, and the message
client 1 sends appears in **both** clients (`[name] text`).

### 2. New client joins mid-chat

**Steps**
1. With a chat already running (scenario 1), start a third client.
2. It prints `[chatapp] Connected to leader=<idA>`.
3. Send a message from any client.

**Expected:** the new client connects to the current leader and receives every message sent **from
the moment it joins** (after it sends its own first line to register).

> **Known limitation:** a joining *client* does not receive the back-history of messages sent before
> it joined - only servers (replicas) sync full history. If full back-history for clients is wanted,
> it is a small follow-up (the leader sends a `HISTORY_SNAPSHOT` to a client on connect). Note this
> to the audience rather than claim it.

### 3. Kill the leader - failover under ~10 s, chat continues

**Steps**
1. From scenario 1, identify the leader (the host that logged `event=leader_elected`).
2. Kill its server (`Ctrl+C`, or `kill -9 <pid>`).
3. Keep typing in a client.

**Expected logs (a surviving server)**
```
event=peer_dead ... deadPeer=<idA>
event=election_started myId=<idB> ...
event=answer_sent / event=answer_received        # Bully exchange among survivors
event=leader_elected myId=<idB>                  # next-highest survivor wins
```
**Expected (clients):** `[chatapp] Leader changed ... reconnecting` (or `Connection lost,
rediscovering`) then `[chatapp] Connected to leader=<idB>`.
**Pass:** within ~6 s (heartbeat timeout) + ~2 s (election) a new leader is elected, clients
reconnect automatically, and chat resumes. Lines typed during the gap are delivered after reconnect.

### 4. Restart the killed server - rejoins as replica, syncs history

**Steps**
1. After scenario 3, restart the server that was killed.
2. It draws a **new** random id.

**Expected logs (restarted server)**
```
event=server_id_auto serverId=<idA2> source=random
event=peer_discovered ... / event=group_view ... size=3
event=connected_to_leader ... leaderId=<idB>
event=history_snapshot_applied ... entries=<n>     # full history pulled from the leader
```
**Pass:** the server rejoins the group, connects to the current leader as a replica, and pulls the
existing history (`entries` matches the messages sent so far). If its new id happens to be higher
than the current leader, it bullies its way to leader (`event=leader_rejected` on the other, then a
new election) - also acceptable.

### 5. Kill a non-leader replica - nothing visible to clients

**Steps**
1. From scenario 1, kill a server that is **not** the leader.
2. Keep chatting.

**Expected logs (leader + other survivor)**
```
event=peer_dead ... deadPeer=<idC>
event=group_view ... size=2 ids=[<idB>, <idA>]
```
**Pass:** no leader change, no client reconnect, chat is uninterrupted; the group view shrinks to 2.

---

## Edge cases (issue #21)

### E1. Simultaneous boot - converge to the same group view

**Steps:** start all servers within a second of each other (cold start).
**Expected:** brief election churn is fine, but every server ends with the **same** `group_view`
(`size=N`, identical id set) and the **same** `leaderId` (the highest). `ColdStartElectionIT`
exercises this in CI; on hardware, confirm both consoles show the same `size` and the same
`leader_elected`/`leader_accepted` target.
**Pass:** all servers agree on the group view and the leader.

### E2. Heartbeat jitter - no false-positive peer-death

**Steps:** while the cluster is idle, watch for a minute (a slow/loaded peer may be 1-2 s late with
a heartbeat).
**Expected:** `group_view ... size=N` stays stable; **no** `event=peer_dead` for a live peer. The
6 s dead-timeout is 3x the 2 s heartbeat interval, so a 1-2 s jitter never trips it.
**Pass:** no spurious `peer_dead` and no spurious election while every server is alive.

### E3. Late-joining replica - history sync, no duplicates

**Steps:** run a chat for a while (history has several messages), then start a new server.
**Expected:** the late server logs `event=connected_to_leader` then `event=history_snapshot_applied
entries=<n>` where `<n>` equals the number of messages so far - once, with no duplicates. Continued
`STATE_SYNC` deltas keep it current.
**Pass:** the late replica's history matches the leader's exactly (same count), no duplicated lines.

---

## What "green" looks like for the demo

- Three servers, one `leader_elected`, the rest `leader_accepted`, `group_view size=3`.
- Two+ clients exchanging messages live.
- Leader killed -> next-highest elected within ~8 s, clients reconnect, chat continues.
- Killed server restarted -> rejoins as replica and pulls history.
- No `peer_dead` for a server that is actually alive.
