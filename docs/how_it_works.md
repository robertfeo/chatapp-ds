# How chatapp-ds works / Wie chatapp-ds funktioniert

This document explains, based on the actual code and the architecture diagram, how the
system works end to end: discovery, heartbeats, leader election, the chat flow over TCP,
TCP state sync, and what snapshots really are in this program.

Dieses Dokument erklärt anhand des tatsächlichen Codes und des Architekturdiagramms, wie
das System als Ganzes funktioniert: Discovery, Heartbeats, Leader-Wahl, der Chat-Fluss
über TCP, TCP State Sync und was Snapshots in diesem Programm wirklich sind.

The English version comes first; die deutsche Fassung beginnt bei
[Deutsche Fassung](#deutsche-fassung).

![Architecture diagram](diagrams/architecture.png)

## English version

### The big picture

chatapp-ds is a distributed group chat for a local network. Three server processes form a
cluster, and exactly one of them, the **leader**, talks to clients. Every client sends its
chat lines to the leader over TCP, and the leader fans each accepted line back out to all
connected clients. The other two servers are **replicas**: they hold a live copy of the
chat history, so any of them can take over as leader when the current leader dies. The
whole point of the replica machinery (heartbeats, election, state sync) is that a leader
crash interrupts the chat for a few seconds at most and loses no server-side state.

Each server draws a **random numeric id** at startup
([`ServerConfig.deriveServerId`](../src/main/java/com/chatapp/config/ServerConfig.java),
range 1 to `Integer.MAX_VALUE`); the `SERVER_ID` environment variable is only an optional
override for deterministic ids in tests. One rule governs leadership everywhere: **the
highest live id wins**.

Every packet on the wire, UDP or TCP, is a single JSON object: one
[`Message`](../src/main/java/com/chatapp/protocol/Message.java) (a sealed interface,
serialized by [`Codec`](../src/main/java/com/chatapp/protocol/Codec.java)). The full
message catalogue lives in [protocol.md](protocol.md). Two ports carry everything:

| Port | Transport | Used for |
| - | - | - |
| `DISCOVERY_PORT` (default 45678) | UDP | discovery, heartbeats, election |
| `LISTEN_PORT` (default 6000) | TCP | client chat and replica state sync |

In the diagram above: solid arrows are TCP (clients to the leader, and the leader's state
sync to the replicas), dotted arrows are UDP (the heartbeat mesh between servers, and the
broadcast bubble that all servers and clients use for discovery and election).

### Discovery (UDP broadcast)

Code: [`DiscoveryService`](../src/main/java/com/chatapp/discovery/DiscoveryService.java),
[`GroupView`](../src/main/java/com/chatapp/discovery/GroupView.java).

Nobody configures peer addresses. On startup a server broadcasts a `DISCOVERY_HELLO`
carrying its advertised host and TCP port. The hello goes to **every live interface's
broadcast address** plus the configured fallback (`255.255.255.255` by default), so it
still leaves the real LAN interface on a machine cluttered with VPN and virtual adapters.

Every server that hears a hello does two things:

1. If the sender is a server, it stores the peer (id, host, TCP port) in its local
   `GroupView`. Client hellos are deliberately **not** stored: a client has no listening
   port and sends no heartbeats, so tracking it would only churn the view.
2. It replies by UDP **unicast** with a `DISCOVERY_REPLY` containing its own address and
   the **current leader id** (or null when no leader is known yet). The receiver of a
   reply stores that peer too.

A scheduled task re-broadcasts the hello every 10 seconds, so late joiners and restarted
servers are eventually seen by everyone. The `GroupView` is a thread-safe map from id to
peer, and updates are idempotent: hearing about the same peer twice changes nothing.

Two practical notes baked into the code: the default port is 45678 because UDP 4500 is the
IPsec NAT-T port that the Windows IKEEXT service silently swallows, and the socket sets
`SO_REUSEPORT` so several server processes can share the discovery port on one machine
(the localhost dev setup); on a real LAN each host has its own address anyway.

Clients use the same mechanism to find the leader: broadcast a hello, collect replies for
3 seconds, then prefer the reply whose sender **is** the leader (`senderId == leaderId`),
falling back to any reply that points at a leader for which a direct reply also exists.

### Heartbeats (UDP unicast, 2 s / 6 s)

Code: [`HeartbeatService`](../src/main/java/com/chatapp/heartbeat/HeartbeatService.java).

Every 2 seconds (`HEARTBEAT_INTERVAL_S`) each server sends a `HEARTBEAT` datagram directly
to every peer in its `GroupView`, so the servers form a full heartbeat mesh. Each server
records per peer when it last heard a heartbeat. On every tick it also checks liveness: a
peer silent for more than 6 seconds (`PEER_DEAD_TIMEOUT_S`) is declared **dead**, removed
from the `GroupView`, and reported to a callback. Newly discovered peers get a grace
period: their "last seen" clock starts at the moment of the first send attempt to them.

The callback (wired in
[`ServerMain`](../src/main/java/com/chatapp/server/ServerMain.java)) implements failover:
**if the dead peer is the current leader, start an election.** A dead replica is simply
dropped from the view; nothing else needs to happen.

Heartbeats also piggyback two counters, `connectedClients` and `connectedReplicas`, so the
replicas' terminal dashboards can display the leader's live connection counts instead of
their own (which are always zero, because clients connect only to the leader).

### Leader election (Bully, highest id wins)

Code: [`ElectionService`](../src/main/java/com/chatapp/election/ElectionService.java),
[`Election`](../src/main/java/com/chatapp/election/Election.java).

The election is the **Bully algorithm** (Garcia-Molina 1982), the highest-id-wins scheme
from the lecture. It is not LCR and not a ring: the servers are a small, fully connected
group that already knows each other's ids from discovery. Three message types are used,
all sent by UDP broadcast on the discovery port:

| Message | Meaning |
| - | - |
| `ELECTION` | A server calls an election and announces its candidacy. |
| `ANSWER` | A higher-id server says "I am alive", suppressing the lower candidate. |
| `I_AM_LEADER` | The winner (coordinator) announces itself to everyone. |

Broadcasting instead of unicasting is deliberate: receivers simply compare the sender's id
with their own, which reproduces the classic "send only to higher ids" semantics without
maintaining per-peer connections, and it works on localhost where all processes share one
UDP port.

A round on server P runs like this:

1. P checks its `GroupView` first: if no known live peer outranks it, it **wins outright**
   and immediately broadcasts `I_AM_LEADER` (no pointless waiting for answers that cannot
   come). This is the common fast path right after a leader death.
2. Otherwise P broadcasts `ELECTION` and waits 2 seconds (`ELECTION_TIMEOUT_S`). No
   `ANSWER` from a higher id within that window means P wins and declares itself.
3. If a higher id did answer, P stands down and waits another 2 seconds for that server's
   `I_AM_LEADER`. If none arrives (the higher server crashed in between), P restarts the
   election.
4. Rules on receipt: an `ELECTION` from a *lower* id gets an `ANSWER` and triggers P's own
   election; an `I_AM_LEADER` with an id lower than P's own is rejected and P starts a new
   election (that is the "bully" part). An `I_AM_LEADER` with an id at least P's own is
   accepted.

Only one round runs at a time per server (an atomic guard), and accepting the same leader
twice is idempotent, so duplicated broadcasts do no harm.

There is no prior leader to die on a **cold start**, so each server schedules a one-shot
bootstrap election 3 seconds after startup (`ELECTION_BOOTSTRAP_DELAY_S`), skipped if some
leader is already known by then. The delay gives discovery time to converge so the true
highest id wins the first round. A side effect of the bully rules: a **new server joining
with a higher id takes leadership over** from the incumbent.

When the accepted leader actually changes, two callbacks fire in `ServerMain`: the TCP
server pushes an `I_AM_LEADER` notification to all of its connected clients and replicas
(so they migrate immediately instead of waiting for timeouts), and the replica connector
re-dials the new leader.

### Chat over TCP

Code: [`TcpServer`](../src/main/java/com/chatapp/server/TcpServer.java),
[`ClientSession`](../src/main/java/com/chatapp/server/ClientSession.java),
[`ChatClient`](../src/main/java/com/chatapp/client/ChatClient.java).

One TCP port serves both kinds of peers. A new connection is classified by the
`senderRole` of the **first message** it sends: `server` means a replica (state-sync
link), `client` means a chat client. Each session runs on its own Java 21 virtual thread;
framing is newline-delimited JSON, one message per line.

The chat flow on the leader:

1. A client sends `CHAT` with its display name and text.
2. The leader (only a server that currently believes it is the leader accepts chats)
   appends a `ChatEntry(from, text, ts)` to its in-memory
   [`ChatHistory`](../src/main/java/com/chatapp/server/ChatHistory.java).
3. It fans the `CHAT` back out to **all connected clients**, including the sender (the
   sender's own line coming back is the delivery confirmation).
4. It pushes a `STATE_SYNC` containing that one new entry to **all connected replicas**.

Message **ordering** needs no vector clocks by design: the single leader serializes all
chat lines (its append order is the global order), and TCP preserves that order per
connection to every client and replica. The `ts` field is for logs only.

Clients receive no history replay when they connect; they see messages from that moment
on. The replicated history exists for server-side fault tolerance, not for client catch-up.

The client itself is a reconnect loop: discover the leader by broadcast, connect, then run
a receive loop and a stdin loop. On a TCP drop or an `I_AM_LEADER` notification it goes
back to discovery. Lines typed while disconnected are buffered and sent after reconnect.

### TCP state sync

Code: [`ReplicaConnector`](../src/main/java/com/chatapp/server/ReplicaConnector.java).

"TCP state sync" in the diagram is the leader-to-replica replication channel. Every server
that is **not** the leader runs a `ReplicaConnector` that:

1. Looks up the leader's address in the `GroupView` and dials its TCP port.
2. Sends a `HISTORY_REQUEST` right after connecting.
3. Receives a `HISTORY_SNAPSHOT` (the full history) and **replaces** its local
   `ChatHistory` with it.
4. Then stays in a receive loop and **appends** every incoming `STATE_SYNC` delta (one
   entry per accepted chat message) to its local history.

So replication is *snapshot once, then deltas*. When the election callback reports a new
leader, the connector drops the old link and re-dials; a server that just became leader
only disconnects its outgoing link (leaders do not sync from anyone). This is exactly why
failover is safe: at promotion time the new leader's local `ChatHistory` already contains
everything the old leader had accepted and replicated.

### Snapshots: what they actually are

The word "snapshot" appears in two related senses in this codebase, and neither means
persistence:

1. **The `HISTORY_SNAPSHOT` wire message.** The full chat history in a single message,
   sent by the leader as the answer to a replica's `HISTORY_REQUEST` when that replica
   joins or rejoins (including right after every failover reconnect). The replica applies
   it with `ChatHistory.resetFromSnapshot`, clearing its local list and copying the
   snapshot in. This is the catch-up mechanism: a replica that was down, or whose sync
   link dropped, missed an unknown number of deltas, so patching is impossible and it
   takes the whole state fresh instead. After the snapshot, incremental `STATE_SYNC`
   deltas keep it current.
2. **In-memory copy snapshots for thread safety.** `ChatHistory.snapshot()` returns an
   immutable copy of the list (used both as the payload of `HISTORY_SNAPSHOT` and for safe
   reads), and `GroupView.snapshot()` returns an immutable copy of the peer set so that
   heartbeat and election code can iterate a stable view while discovery concurrently
   mutates the map. These are consistency tools inside one process, nothing goes on disk.

Nothing is ever persisted: the chat history lives in RAM only, and durability comes from
**redundancy across the three servers**, not from storage. If servers die one at a time,
the history survives through snapshot-plus-delta replication; if all three die at once, it
is gone. One known limitation follows from random ids plus the bully rule: a brand-new
server that joins with a higher id wins the election while holding an **empty** history,
and the demoted servers would then reset from that empty snapshot. The design assumes
failover promotes a replica that is already in sync, which holds in the intended demo
setup (servers join before chatting starts, ids stay fixed while running).

### Failover, end to end

1. t = 0: the leader process dies. Client TCP connections break at once.
2. Within at most 6 seconds every replica's liveness check declares the leader dead,
   removes it from the `GroupView`, and starts an election.
3. The highest surviving id takes the fast path (nothing in its view outranks it) and
   broadcasts `I_AM_LEADER` essentially immediately; a slower interleaving is bounded by
   the 2-second answer window. Lower ids accept the announcement.
4. The other replica re-dials the new leader, requests the history, gets a
   `HISTORY_SNAPSHOT`, and resumes delta sync.
5. Clients rediscover by broadcast. While the death is not yet detected, replies still
   name the dead leader and the client keeps retrying every 2 seconds; once the new leader
   is announced, replies carry its id, the client connects and flushes any lines the user
   typed during the outage.

Net effect for a user: chat freezes for roughly 6 to 10 seconds, then continues on the new
leader with nothing lost that the old leader had accepted.

### Key constants and configuration

Constants live in [`Config`](../src/main/java/com/chatapp/config/Config.java):

| Constant | Value | Meaning |
| - | - | - |
| `HEARTBEAT_INTERVAL_S` | 2 s | heartbeat send interval per peer |
| `PEER_DEAD_TIMEOUT_S` | 6 s | silence after which a peer is dead |
| `ELECTION_TIMEOUT_S` | 2 s | wait for `ANSWER`, then for `I_AM_LEADER` |
| `ELECTION_BOOTSTRAP_DELAY_S` | 3 s | one-shot cold-start election delay |
| `DISCOVERY_REANNOUNCE_S` | 10 s | re-broadcast interval of the hello |

Configuration is environment variables only (same fat-jar on every host):

| Variable | Default | Meaning |
| - | - | - |
| `SERVER_ID` | random | optional override for deterministic ids |
| `LISTEN_HOST` | `0.0.0.0` | TCP bind address |
| `LISTEN_PORT` | `6000` | TCP chat / state-sync port |
| `DISCOVERY_PORT` | `45678` | UDP discovery / heartbeat / election port |
| `BROADCAST_ADDR` | `255.255.255.255` | fallback broadcast target |

## Deutsche Fassung

### Das Gesamtbild

chatapp-ds ist ein verteilter Gruppenchat für ein lokales Netz. Drei Serverprozesse bilden
einen Cluster, und genau einer von ihnen, der **Leader**, kommuniziert mit den Clients.
Jeder Client schickt seine Chatzeilen per TCP an den Leader, und der Leader verteilt jede
akzeptierte Zeile zurück an alle verbundenen Clients. Die beiden anderen Server sind
**Replikate**: Sie halten eine laufend aktualisierte Kopie der Chat-Historie, damit jedes
von ihnen die Leader-Rolle übernehmen kann, wenn der aktuelle Leader ausfällt. Der ganze
Replikationsapparat (Heartbeats, Wahl, State Sync) existiert genau dafür: Ein
Leader-Absturz unterbricht den Chat höchstens für ein paar Sekunden und verliert keinen
serverseitigen Zustand.

Jeder Server zieht beim Start eine **zufällige numerische Id**
([`ServerConfig.deriveServerId`](../src/main/java/com/chatapp/config/ServerConfig.java),
Bereich 1 bis `Integer.MAX_VALUE`); die Umgebungsvariable `SERVER_ID` ist nur ein
optionales Override für deterministische Ids in Tests. Überall im System gilt eine einzige
Regel für die Führung: **die höchste lebende Id gewinnt**.

Jedes Paket auf der Leitung, ob UDP oder TCP, ist ein einzelnes JSON-Objekt: eine
[`Message`](../src/main/java/com/chatapp/protocol/Message.java) (ein sealed interface,
serialisiert durch [`Codec`](../src/main/java/com/chatapp/protocol/Codec.java)). Der
vollständige Nachrichtenkatalog steht in [protocol.md](protocol.md). Zwei Ports tragen
alles:

| Port | Transport | Zweck |
| - | - | - |
| `DISCOVERY_PORT` (Standard 45678) | UDP | Discovery, Heartbeats, Wahl |
| `LISTEN_PORT` (Standard 6000) | TCP | Client-Chat und Replikat-State-Sync |

Im Diagramm oben: Durchgezogene Pfeile sind TCP (Clients zum Leader, und der State Sync
des Leaders zu den Replikaten), gepunktete Pfeile sind UDP (das Heartbeat-Netz zwischen
den Servern und die Broadcast-Blase, die alle Server und Clients für Discovery und Wahl
nutzen).

### Discovery (UDP-Broadcast)

Code: [`DiscoveryService`](../src/main/java/com/chatapp/discovery/DiscoveryService.java),
[`GroupView`](../src/main/java/com/chatapp/discovery/GroupView.java).

Niemand konfiguriert Peer-Adressen von Hand. Beim Start broadcastet ein Server ein
`DISCOVERY_HELLO` mit seinem beworbenen Host und TCP-Port. Das Hello geht an die
**Broadcast-Adresse jedes aktiven Interfaces** plus an das konfigurierte Fallback
(standardmäßig `255.255.255.255`), damit es auch auf einer Maschine voller VPN- und
virtueller Adapter das echte LAN-Interface verlässt.

Jeder Server, der ein Hello empfängt, tut zwei Dinge:

1. Ist der Absender ein Server, speichert er den Peer (Id, Host, TCP-Port) in seiner
   lokalen `GroupView`. Client-Hellos werden bewusst **nicht** gespeichert: Ein Client hat
   keinen Listening-Port und sendet keine Heartbeats, er würde die Sicht nur stören.
2. Er antwortet per UDP-**Unicast** mit einem `DISCOVERY_REPLY`, das seine eigene Adresse
   und die **aktuell bekannte Leader-Id** enthält (oder null, wenn noch kein Leader
   bekannt ist). Der Empfänger einer Reply speichert diesen Peer ebenfalls.

Ein geplanter Task wiederholt das Hello alle 10 Sekunden, sodass Nachzügler und neu
gestartete Server irgendwann von allen gesehen werden. Die `GroupView` ist eine
threadsichere Map von Id auf Peer, und Updates sind idempotent: Denselben Peer zweimal zu
lernen ändert nichts.

Zwei praktische Details stecken direkt im Code: Der Standardport ist 45678, weil UDP 4500
der IPsec-NAT-T-Port ist, den der Windows-Dienst IKEEXT stillschweigend schluckt, und der
Socket setzt `SO_REUSEPORT`, damit mehrere Serverprozesse auf einer Maschine denselben
Discovery-Port teilen können (das localhost-Dev-Setup); im echten LAN hat ohnehin jeder
Host seine eigene Adresse.

Clients finden den Leader mit demselben Mechanismus: Hello broadcasten, 3 Sekunden lang
Replies sammeln, dann die Reply bevorzugen, deren Absender selbst der Leader **ist**
(`senderId == leaderId`), mit Fallback auf eine Reply, die auf einen Leader zeigt, von dem
ebenfalls eine direkte Reply vorliegt.

### Heartbeats (UDP-Unicast, 2 s / 6 s)

Code: [`HeartbeatService`](../src/main/java/com/chatapp/heartbeat/HeartbeatService.java).

Alle 2 Sekunden (`HEARTBEAT_INTERVAL_S`) schickt jeder Server ein `HEARTBEAT`-Datagramm
direkt an jeden Peer in seiner `GroupView`; die Server bilden also ein vollständiges
Heartbeat-Netz. Jeder Server merkt sich pro Peer, wann er zuletzt einen Heartbeat gehört
hat. Bei jedem Takt prüft er außerdem die Lebendigkeit: Ein Peer, der länger als
6 Sekunden (`PEER_DEAD_TIMEOUT_S`) schweigt, wird für **tot** erklärt, aus der `GroupView`
entfernt und an einen Callback gemeldet. Frisch entdeckte Peers bekommen eine Schonfrist:
Ihre "zuletzt gesehen"-Uhr startet mit dem ersten Sendeversuch an sie.

Der Callback (verdrahtet in
[`ServerMain`](../src/main/java/com/chatapp/server/ServerMain.java)) realisiert das
Failover: **Ist der tote Peer der aktuelle Leader, wird eine Wahl gestartet.** Ein totes
Replikat wird einfach aus der Sicht entfernt; mehr ist nicht nötig.

Heartbeats transportieren zusätzlich zwei Zähler, `connectedClients` und
`connectedReplicas`, damit die Terminal-Dashboards der Replikate die live
Verbindungszahlen des Leaders anzeigen können statt ihrer eigenen (die immer null sind,
weil Clients nur zum Leader verbinden).

### Leader-Wahl (Bully, höchste Id gewinnt)

Code: [`ElectionService`](../src/main/java/com/chatapp/election/ElectionService.java),
[`Election`](../src/main/java/com/chatapp/election/Election.java).

Die Wahl ist der **Bully-Algorithmus** (Garcia-Molina 1982), das
Höchste-Id-gewinnt-Verfahren aus der Vorlesung. Es ist nicht LCR und kein Ring: Die Server
sind eine kleine, vollständig verbundene Gruppe, die die Ids der anderen bereits aus der
Discovery kennt. Drei Nachrichtentypen kommen zum Einsatz, alle per UDP-Broadcast auf dem
Discovery-Port:

| Nachricht | Bedeutung |
| - | - |
| `ELECTION` | Ein Server ruft eine Wahl aus und meldet seine Kandidatur an. |
| `ANSWER` | Ein Server mit höherer Id sagt "ich lebe" und stoppt den niedrigeren Kandidaten. |
| `I_AM_LEADER` | Der Gewinner (Koordinator) verkündet sich allen. |

Broadcast statt Unicast ist Absicht: Empfänger vergleichen einfach die Absender-Id mit der
eigenen, was die klassische Semantik "nur an höhere Ids senden" nachbildet, ohne
Verbindungen pro Peer zu pflegen, und es funktioniert auch auf localhost, wo alle Prozesse
einen UDP-Port teilen.

Eine Runde auf Server P läuft so ab:

1. P schaut zuerst in seine `GroupView`: Wenn kein bekannter lebender Peer höher liegt,
   **gewinnt P sofort** und broadcastet direkt `I_AM_LEADER` (kein sinnloses Warten auf
   Antworten, die nicht kommen können). Das ist der übliche schnelle Pfad direkt nach
   einem Leader-Tod.
2. Andernfalls broadcastet P `ELECTION` und wartet 2 Sekunden (`ELECTION_TIMEOUT_S`).
   Kommt in dieser Zeit kein `ANSWER` einer höheren Id, gewinnt P und erklärt sich selbst.
3. Hat eine höhere Id geantwortet, tritt P zurück und wartet weitere 2 Sekunden auf deren
   `I_AM_LEADER`. Kommt keines (der höhere Server ist zwischendurch abgestürzt), startet P
   die Wahl neu.
4. Empfangsregeln: Ein `ELECTION` von einer *niedrigeren* Id bekommt ein `ANSWER`, und P
   startet seine eigene Wahl; ein `I_AM_LEADER` mit einer Id unterhalb der eigenen wird
   abgelehnt, und P startet eine neue Wahl (das ist der "Bully"-Teil). Ein `I_AM_LEADER`
   mit einer Id mindestens gleich der eigenen wird akzeptiert.

Pro Server läuft immer nur eine Runde (atomarer Guard), und denselben Leader zweimal zu
akzeptieren ist idempotent, doppelt zugestellte Broadcasts richten also keinen Schaden an.

Beim **Kaltstart** gibt es keinen früheren Leader, der sterben könnte, deshalb plant jeder
Server 3 Sekunden nach dem Start (`ELECTION_BOOTSTRAP_DELAY_S`) eine einmalige
Bootstrap-Wahl, die übersprungen wird, wenn bis dahin schon ein Leader bekannt ist. Die
Verzögerung gibt der Discovery Zeit zu konvergieren, damit gleich in der ersten Runde die
wirklich höchste Id gewinnt. Eine Folge der Bully-Regeln: Ein **neu beitretender Server
mit höherer Id übernimmt die Führung** vom Amtsinhaber.

Ändert sich der akzeptierte Leader tatsächlich, feuern in `ServerMain` zwei Callbacks: Der
TCP-Server schickt allen verbundenen Clients und Replikaten eine
`I_AM_LEADER`-Benachrichtigung (damit sie sofort umziehen statt auf Timeouts zu warten),
und der Replica-Connector wählt sich neu beim neuen Leader ein.

### Chat über TCP

Code: [`TcpServer`](../src/main/java/com/chatapp/server/TcpServer.java),
[`ClientSession`](../src/main/java/com/chatapp/server/ClientSession.java),
[`ChatClient`](../src/main/java/com/chatapp/client/ChatClient.java).

Ein einziger TCP-Port bedient beide Arten von Gegenstellen. Eine neue Verbindung wird
anhand der `senderRole` der **ersten empfangenen Nachricht** klassifiziert: `server`
bedeutet Replikat (State-Sync-Verbindung), `client` bedeutet Chat-Client. Jede Session
läuft auf einem eigenen virtuellen Thread (Java 21); das Framing ist zeilengetrenntes
JSON, eine Nachricht pro Zeile.

Der Chat-Fluss auf dem Leader:

1. Ein Client sendet `CHAT` mit Anzeigenamen und Text.
2. Der Leader (nur ein Server, der sich gerade selbst für den Leader hält, akzeptiert
   Chats) hängt ein `ChatEntry(from, text, ts)` an seine
   [`ChatHistory`](../src/main/java/com/chatapp/server/ChatHistory.java) im Speicher an.
3. Er verteilt das `CHAT` an **alle verbundenen Clients**, auch an den Absender (die
   eigene zurückkommende Zeile ist die Zustellbestätigung).
4. Er schickt ein `STATE_SYNC` mit genau diesem neuen Eintrag an **alle verbundenen
   Replikate**.

Die **Reihenfolge** der Nachrichten braucht konstruktionsbedingt keine Vektoruhren: Der
einzelne Leader serialisiert alle Chatzeilen (seine Append-Reihenfolge ist die globale
Ordnung), und TCP erhält diese Reihenfolge pro Verbindung zu jedem Client und Replikat.
Das Feld `ts` dient nur den Logs.

Clients bekommen beim Verbinden keine Historie nachgespielt; sie sehen Nachrichten ab
diesem Moment. Die replizierte Historie existiert für die serverseitige Fehlertoleranz,
nicht zum Aufholen der Clients.

Der Client selbst ist eine Reconnect-Schleife: Leader per Broadcast finden, verbinden,
dann eine Empfangs- und eine stdin-Schleife fahren. Bei einem TCP-Abriss oder einer
`I_AM_LEADER`-Benachrichtigung geht er zurück zur Discovery. Zeilen, die während der
Trennung getippt wurden, werden gepuffert und nach dem Reconnect gesendet.

### TCP State Sync

Code: [`ReplicaConnector`](../src/main/java/com/chatapp/server/ReplicaConnector.java).

"TCP state sync" im Diagramm ist der Replikationskanal vom Leader zu den Replikaten. Jeder
Server, der **nicht** Leader ist, betreibt einen `ReplicaConnector`, der:

1. die Adresse des Leaders in der `GroupView` nachschlägt und dessen TCP-Port anwählt,
2. direkt nach dem Verbinden ein `HISTORY_REQUEST` schickt,
3. ein `HISTORY_SNAPSHOT` (die komplette Historie) empfängt und seine lokale
   `ChatHistory` damit **ersetzt**,
4. danach in einer Empfangsschleife bleibt und jedes eintreffende `STATE_SYNC`-Delta (ein
   Eintrag pro akzeptierter Chatnachricht) an die lokale Historie **anhängt**.

Replikation ist also *einmal Snapshot, dann Deltas*. Meldet der Wahl-Callback einen neuen
Leader, trennt der Connector die alte Verbindung und wählt neu; ein Server, der gerade
selbst Leader geworden ist, trennt nur seine ausgehende Verbindung (Leader synchronisieren
sich von niemandem). Genau deshalb ist das Failover sicher: Im Moment der Beförderung
enthält die lokale `ChatHistory` des neuen Leaders bereits alles, was der alte Leader
akzeptiert und repliziert hatte.

### Snapshots: was sie wirklich sind

Das Wort "Snapshot" taucht in diesem Code in zwei verwandten Bedeutungen auf, und keine
davon heißt Persistenz:

1. **Die Wire-Nachricht `HISTORY_SNAPSHOT`.** Die komplette Chat-Historie in einer
   einzigen Nachricht, vom Leader als Antwort auf das `HISTORY_REQUEST` eines Replikats
   gesendet, wenn dieses beitritt oder wieder beitritt (auch direkt nach jedem
   Failover-Reconnect). Das Replikat wendet sie mit `ChatHistory.resetFromSnapshot` an:
   lokale Liste leeren, Snapshot hineinkopieren. Das ist der Aufholmechanismus: Ein
   Replikat, das down war oder dessen Sync-Verbindung abriss, hat eine unbekannte Zahl von
   Deltas verpasst; Flicken ist unmöglich, also übernimmt es stattdessen den kompletten
   Zustand frisch. Nach dem Snapshot halten inkrementelle `STATE_SYNC`-Deltas es aktuell.
2. **In-Memory-Kopien für Threadsicherheit.** `ChatHistory.snapshot()` liefert eine
   unveränderliche Kopie der Liste (zugleich die Nutzlast von `HISTORY_SNAPSHOT` und der
   sichere Lesepfad), und `GroupView.snapshot()` liefert eine unveränderliche Kopie der
   Peer-Menge, damit Heartbeat- und Wahl-Code über eine stabile Sicht iterieren können,
   während die Discovery die Map nebenläufig verändert. Das sind Konsistenzwerkzeuge
   innerhalb eines Prozesses, nichts landet auf der Platte.

Es wird nie etwas persistiert: Die Chat-Historie lebt nur im RAM, und die Haltbarkeit
kommt aus der **Redundanz über die drei Server**, nicht aus Speicherung. Sterben die
Server nacheinander, überlebt die Historie dank Snapshot-plus-Delta-Replikation; sterben
alle drei gleichzeitig, ist sie weg. Aus zufälligen Ids plus Bully-Regel folgt eine
bekannte Einschränkung: Ein ganz neuer Server, der mit höherer Id beitritt, gewinnt die
Wahl mit **leerer** Historie, und die degradierten Server würden sich anschließend von
diesem leeren Snapshot zurücksetzen. Das Design nimmt an, dass das Failover ein bereits
synchrones Replikat befördert, was im vorgesehenen Demo-Ablauf gilt (Server treten bei,
bevor gechattet wird; Ids bleiben zur Laufzeit fest).

### Failover, Ende zu Ende

1. t = 0: Der Leader-Prozess stirbt. Die TCP-Verbindungen der Clients reißen sofort ab.
2. Innerhalb von höchstens 6 Sekunden erklärt die Lebendigkeitsprüfung jedes Replikats den
   Leader für tot, entfernt ihn aus der `GroupView` und startet eine Wahl.
3. Die höchste überlebende Id nimmt den schnellen Pfad (nichts in ihrer Sicht liegt höher)
   und broadcastet praktisch sofort `I_AM_LEADER`; ein ungünstigeres Interleaving ist
   durch das 2-Sekunden-Antwortfenster begrenzt. Niedrigere Ids akzeptieren die
   Ankündigung.
4. Das andere Replikat wählt sich beim neuen Leader ein, fordert die Historie an, erhält
   ein `HISTORY_SNAPSHOT` und setzt die Delta-Synchronisation fort.
5. Die Clients suchen per Broadcast neu. Solange der Tod noch nicht erkannt ist, nennen
   die Replies weiterhin den toten Leader, und der Client probiert es alle 2 Sekunden
   erneut; sobald der neue Leader verkündet ist, tragen die Replies dessen Id, der Client
   verbindet sich und sendet alle Zeilen nach, die der Nutzer während des Ausfalls getippt
   hat.

Nettoeffekt für den Nutzer: Der Chat friert für etwa 6 bis 10 Sekunden ein und läuft dann
auf dem neuen Leader weiter, ohne dass etwas verloren geht, was der alte Leader akzeptiert
hatte.

### Wichtige Konstanten und Konfiguration

Die Konstanten stehen in [`Config`](../src/main/java/com/chatapp/config/Config.java):

| Konstante | Wert | Bedeutung |
| - | - | - |
| `HEARTBEAT_INTERVAL_S` | 2 s | Sendeintervall der Heartbeats pro Peer |
| `PEER_DEAD_TIMEOUT_S` | 6 s | Stille, nach der ein Peer als tot gilt |
| `ELECTION_TIMEOUT_S` | 2 s | Warten auf `ANSWER`, danach auf `I_AM_LEADER` |
| `ELECTION_BOOTSTRAP_DELAY_S` | 3 s | Verzögerung der einmaligen Kaltstart-Wahl |
| `DISCOVERY_REANNOUNCE_S` | 10 s | Wiederholintervall des Hello-Broadcasts |

Konfiguriert wird ausschließlich über Umgebungsvariablen (dasselbe Fat-Jar auf jedem
Host):

| Variable | Standard | Bedeutung |
| - | - | - |
| `SERVER_ID` | zufällig | optionales Override für deterministische Ids |
| `LISTEN_HOST` | `0.0.0.0` | TCP-Bind-Adresse |
| `LISTEN_PORT` | `6000` | TCP-Port für Chat und State Sync |
| `DISCOVERY_PORT` | `45678` | UDP-Port für Discovery, Heartbeat, Wahl |
| `BROADCAST_ADDR` | `255.255.255.255` | Fallback-Broadcast-Ziel |
