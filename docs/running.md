# Running chatapp-ds

How to get the application onto a host and run it, from the quickest path (a
one-file launcher) to a manual build. The system is **zero-config**: a server
detects its own LAN address and broadcast, draws a random id, and uses the
default ports, so there is nothing to set up before the first run.

## Prerequisites

- A **Java 21** (or newer) runtime on every host. Check with `java -version`.
  Install from [Adoptium Temurin](https://adoptium.net/) if it is missing.
- All hosts on the **same LAN or hotspot**. Avoid eduroam and isolating "guest"
  Wi-Fi, which block the UDP broadcast used for discovery; a phone hotspot or a
  home router works.
- On Windows, run on **native Windows** (PowerShell or cmd), not inside WSL:
  WSL2's NAT keeps the broadcast off the LAN.

## Option A: the launcher (recommended)

Two launchers live at the repository root and are attached to each release:

- `start.sh` for Linux, macOS and the Raspberry Pi
- `start.bat` for Windows

Each one verifies that Java 21+ is present (it understands both the modern
`21.x` and the legacy `1.x` version strings) and then finds a runnable jar on
its own, in this order:

1. `target/chatapp.jar`, when the project was already built from source;
2. `chatapp.jar` next to the launcher, when an earlier run downloaded it;
3. build from source (`mvn -DskipTests package`), when the launcher sits in a
   source checkout and Maven is installed;
4. download the latest released `chatapp.jar` next to itself.

So in a cloned repository `./start.sh server` is all you ever type, and on a
bare host the same command downloads the release jar first. Pass `server` or
`client` as the argument:

**Linux, macOS, Raspberry Pi:**

```bash
curl -fLO https://github.com/robertfeo/chatapp-ds/releases/latest/download/start.sh
chmod +x start.sh
./start.sh server      # start a server
./start.sh client      # start a client
```

**Windows (PowerShell or cmd):**

```bat
curl.exe -fLO https://github.com/robertfeo/chatapp-ds/releases/latest/download/start.bat
.\start.bat server
.\start.bat client
```

The jar is cached after the first download or build, so later runs start
immediately. To force a fresh download (for example after a new release),
delete `chatapp.jar` and run the launcher again.

## Option B: run the jar directly

Download `chatapp.jar` from the
[latest release](https://github.com/robertfeo/chatapp-ds/releases/latest) and
run it with any Java 21 runtime:

```bash
java -jar chatapp.jar server
java -jar chatapp.jar client
```

## Option C: build from source

Requires JDK 21 and Maven.

```bash
mvn -B verify     # format check + unit + integration tests
mvn package       # build the shaded fat-jar at target/chatapp.jar
java -jar target/chatapp.jar server
```

With `make`, the same things are one word each: `make package`, `make test`,
and `make server` / `make client` (which build the jar first when it is
missing and then run that role in the foreground).

## The live dashboard

On an interactive terminal each **server** shows a live panel instead of
scrolling logs: its role (leader or replica), the current leader, the live peer
set, the connected clients and replicas, the chat-history size, the listening
ports, and a feed of the most recent events (elections, joins, chats, failover).
The numbers update a few times a second, and the client/replica counts are the
same on every server (a replica mirrors the leader's counts).

- Press **`q`** or **`Ctrl+C`** to stop the server (this is how you trigger the
  failover demo).
- The full structured log is still written to `logs/server-<id>.log`.
- When output is not a terminal (a pipe or CI) the server prints the plain
  `event=...` logs to stdout instead, unchanged.
- Set `CHATAPP_DASHBOARD=off` to force plain logs on a terminal too.

A **client** is a line-based chat: type a message and press Enter; it appears on
every connected client. A client only starts receiving after it sends its first
line, so type once in each to wire them up.

## Configuration (optional overrides)

Nothing below is required. The launchers and a bare `java -jar chatapp.jar
server` work with no environment variables; these only override the defaults.

| Variable | Meaning | Default |
|---|---|---|
| `SERVER_ID` | Unique numeric server id. Random when unset; set it only for deterministic ids (several servers on one machine). | auto (random) |
| `LISTEN_HOST` | Address the server binds; the advertised address is auto-detected when this is `0.0.0.0`. | `0.0.0.0` |
| `LISTEN_PORT` | TCP port for chat and state sync. | `6000` |
| `DISCOVERY_PORT` | UDP port for discovery and heartbeats (not 4500: that is Windows' IPsec NAT-T port). | `45678` |
| `BROADCAST_ADDR` | Fallback broadcast target; the server also announces to every interface's own broadcast. | `255.255.255.255` |
| `CLIENT_NAME` | Display name a client uses in chat. | host name |
| `CHATAPP_DASHBOARD` | Set to `off` to disable the live dashboard and use plain logs even on a terminal. | auto (on when interactive) |

## Three-host demo

The graded demo runs on three physical hosts on a private LAN (a Raspberry Pi 4
plus two laptops), each running a server and one or more clients. The host that
draws the highest id becomes the leader. The step-by-step script, including the
failover test, is in [`demo_runbook.md`](demo_runbook.md).

## Troubleshooting

- **`java` not found / wrong version.** The launchers print a clear error and a
  link to Adoptium. Install a Java 21 runtime, or put `java` on `PATH`.
- **Servers never discover each other** (each stays at `group_view ... size=1`).
  The UDP broadcast is being dropped: you are on eduroam or a guest network, or
  on WSL. Move to a hotspot or home router and run servers on native Windows.
- **Windows firewall prompt.** Allow Java on private networks the first time, or
  pre-open the ports:
  `New-NetFirewallRule -DisplayName "chatapp-udp" -Direction Inbound -Protocol UDP -LocalPort 45678 -Action Allow`
  and the same for `-Protocol TCP -LocalPort 6000`.
- **A client prints "No leader found".** Start the servers first and give them a
  few seconds to elect a leader, then start the client.
- **The dashboard looks garbled on an old terminal.** Use a modern terminal
  (Windows Terminal renders best), or run with `CHATAPP_DASHBOARD=off`.
