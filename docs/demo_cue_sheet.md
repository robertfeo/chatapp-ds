# Demo Cue Sheet

The script for the 15-minute slot: **~5 min slides + ~8 min live demo + ~2 min Q&A**. It says who
speaks when, what to type on stage, and exactly when to kill the leader. Pair it with the slide deck
(#24) and the runbook (`docs/demo_runbook.md`).

## Before you walk in (pre-flight, off the clock)

- All hosts on the demo network (hotspot or home router; **not** eduroam, **not** guest Wi-Fi).
- Servers run on **native Windows / the Pi**, never WSL.
- Jar copied to each host; `java -version` shows 21 everywhere.
- One quick rehearsal of the bring-up so the first `group_view size=3` is fast.
- Terminal font large; each server's **live dashboard** (role, leader, peers, clients, history, and
  the event feed) is visible to the room. The `event=` lines called out below appear in the panel's
  feed; the full log is also in `logs/server-<id>.log`.

## Roles

| Role | Person | Does |
|---|---|---|
| Narrator | Samet | drives the slides and the talk track |
| Operator | Robert | types commands, kills the leader on cue |
| Backup | Ayham | watches client windows, calls out what the audience should see |

## Part 1 - Slides (~5 min, Samet narrates)

1. **Project overview** - distributed chat in Java 21; clients talk to one elected leader; servers
   are replicated and tolerate the crash of any one, including the leader.
2. **Architecture** - show `docs/diagrams/architecture.png`. Point out: TCP chat (client↔leader),
   TCP state sync (leader→replicas), UDP heartbeats (server↔server), UDP broadcast discovery.
3. **Dynamic discovery + heartbeats** - UDP broadcast on the subnet, no registry; heartbeats every
   2 s, peer declared dead after 6 s.
4. **Leader election + failover** - the **Bully algorithm** (Garcia-Molina), highest-id-wins;
   `ELECTION` / `ANSWER` / `I_AM_LEADER`; on leader death the next-highest survivor takes over.
5. **What we'll show live** - bring up 3 servers + clients, chat, then kill the leader and watch
   automatic failover with the clients reconnecting on their own.

## Part 2 - Live demo (~8 min, Robert operates, Samet narrates)

> Every server starts with **no arguments and no env vars**. Ids are random; the highest is leader.

**Cue A - bring up the cluster (~1.5 min)**
- Operator: start the server on each of the 3 hosts.
  - Pi / Linux: `./start.sh server`
  - Windows: `.\start.bat server`
- Narrator: "Each picks a random id and finds the others by UDP broadcast."
- Watch for, and read out: `event=group_view ... size=3`, one `event=leader_elected myId=<idA>`,
  the others `event=leader_accepted leaderId=<idA>`.

**Cue B - clients chat (~2 min)**
- Operator: start two clients (e.g. one on the Pi, one on a laptop): `... client`.
- Each prints `Connected to leader=<idA>`. **Type one line in each client first** (registers it).
- Operator types a message in client 1; Backup points out it appears in **both** clients.
- Narrator: "Messages go to the leader over TCP and are fanned back out to every client."

**Cue C - failover, the headline (~3 min)**
- Narrator: "Now we kill the leader and the system heals itself, no manual intervention."
- Operator: on the leader host (the one that logged `leader_elected`), `Ctrl+C` the server.
- Watch the survivors: `event=peer_dead deadPeer=<idA>` → `event=election_started` → Bully exchange
  (`answer_sent` / `answer_received`) → `event=leader_elected myId=<idB>` (next-highest).
- Clients print `Leader changed ... reconnecting` then `Connected to leader=<idB>`.
- Operator: type another message → it still arrives in both clients. Narrator: "Within ~8 seconds a
  new leader was elected and the clients reconnected on their own."

**Cue D - rejoin (optional, ~1 min if time allows)**
- Operator: restart the killed server. It logs `connected_to_leader` and
  `history_snapshot_applied entries=<n>` - it rejoins as a replica and pulls the chat history.

## Part 3 - Q&A (~2 min)

Likely questions and one-line answers:
- *Which election algorithm?* The Bully algorithm (Garcia-Molina), the highest-id-wins scheme from
  the lecture - not LCR, not a ring.
- *How are ids assigned?* Randomly at startup from a large space (unique with overwhelming
  probability, like DHT node ids); the highest live id is leader.
- *How is a crash detected?* Missing UDP heartbeats: 2 s interval, 6 s dead-timeout.
- *No middleware?* Correct - only the Java standard library for networking; Jackson and Logback are
  utility libraries, not coordination frameworks.
- *Does a new client get the old history?* Servers (replicas) sync full history; a client receives
  messages from when it joins onward (back-history for clients is a known small follow-up).

## If something misbehaves on stage

- A server stays `size=1`: it is on the wrong network or behind WSL/VPN; the others can't reach it.
  Carry on with the two that did converge - the demo still shows election + failover.
- A client says "No leader found": the servers are still electing; wait a few seconds and it
  retries automatically.
- See `docs/demo_runbook.md` Troubleshooting for firewall / broadcast / port notes.

## Timing target

Slides 5:00 + demo 8:00 + Q&A 2:00 = **15:00**. Rehearse twice (issue #25) and trim Cue D first if
you are running long.
