# Multi-host demo runbook (real LAN)

Run the cluster on real devices on one router. This is the deployment the professor asked
for: separate physical hosts, no Docker, no VMs. **Avoid eduroam** (it blocks UDP broadcast);
use a home Wi-Fi router or a phone hotspot.

Server ids are **generated randomly at startup**, so you never assign them by hand. Whichever
server draws the **highest id** becomes the leader (highest-id-wins, Bully algorithm); the leader
can differ on each cold start.

## This test (current setup)

| Host | Role | Notes |
|---|---|---|
| Raspberry Pi 4 | server | id generated randomly |
| Your laptop (Windows) | server + client | run the server in **native Windows PowerShell, not WSL** |
| Third device (Windows) | client only | |

> Which one is leader = whichever drew the higher random id. Check the logs (`event=leader_elected`)
> to see who won; it can change between runs. If you ever need a fixed leader, set `SERVER_ID` to a
> high value on that one host (the optional override); election and failover work the same either way.

## 0. Prerequisites

- **Java 21** on every host. Check with `java -version` (must say 21). Pi: `sudo apt install -y openjdk-21-jre-headless`.
- All three devices on the **same router/SSID**, IPv4, a normal `/24` home network (`192.168.x.y`).
- **Do not run the server in WSL.** WSL2 is behind a virtual NAT; its UDP broadcast never reaches
  the LAN. Build in WSL, but run the jar from native Windows.

## 1. Build and copy the jar

On your dev machine (this repo):

```bash
mvn -q -DskipTests package      # produces target/chatapp.jar (one fat-jar, runs everywhere)
```

Copy `target/chatapp.jar` (and the matching `scripts/`) to each host:

```bash
# to the Pi (replace with the Pi's user@ip):
scp target/chatapp.jar scripts/start_server.sh scripts/start_client.sh pi@<PI_IP>:~/chatapp/
```

For the Windows machines: copy `chatapp.jar` and `scripts\start_server.ps1` / `scripts\start_client.ps1`
into a folder, e.g. `C:\chatapp\`. From WSL: `cp target/chatapp.jar /mnt/c/chatapp/`.

## 2. Sanity-check the IPs

The start scripts auto-detect the IP and broadcast, but confirm the three share a `/24` (so the
broadcast reaches everyone):

- Pi / Linux: `hostname -I`
- Windows (PowerShell): `ipconfig` -> the IPv4 of your Wi-Fi adapter

They should look like `192.168.1.10`, `192.168.1.11`, `192.168.1.12` (same first three octets); the
broadcast is `192.168.1.255`. The server ids are independent of the IPs (they are random).

## 3. Start the servers (no id needed)

**Pi** - SSH in, then:

```bash
cd ~/chatapp
./start_server.sh
```

**Your laptop** - native Windows PowerShell in `C:\chatapp`:

```powershell
.\start_server.ps1
```

If PowerShell refuses the script ("not digitally signed"), either allow local scripts once with
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned; Get-ChildItem .\scripts\*.ps1 | Unblock-File`,
or skip the script entirely and run the jar directly: `java -jar chatapp.jar server` (the server
needs no arguments or env vars).

The first time, Windows Defender Firewall pops up "Allow Java to communicate" - tick **Private
networks** and **Allow access**. (Manual fallback, admin PowerShell:
`New-NetFirewallRule -DisplayName "chatapp-udp" -Direction Inbound -Protocol UDP -LocalPort 45678 -Action Allow`
and the same for `-Protocol TCP -LocalPort 6000`.)

> Discovery uses UDP **45678**, deliberately not 4500: on Windows, port 4500 (IPsec NAT-T) is owned
> by the IKEEXT service, which silently swallows every inbound discovery datagram, so a server on
> 4500 can send but never receive. Any free port avoids it.

**Expected within a few seconds**, on both server logs:

```
event=server_id_auto serverId=1738492054 source=random   # random id, one per host
event=peer_discovered ... peer_id=...                     # they found each other
event=election_started ...
event=leader_elected myId=1738492054                      # the host with the highest id wins
event=leader_accepted myId=... leaderId=1738492054        # the other accepts it
```

If they never discover each other, the broadcast is being dropped: see Troubleshooting.

## 4. Start the clients

No configuration here either; the client discovers the leader and uses this host's name in chat.

**Third device (Windows), client:**

```powershell
.\start_client.ps1
```

**A second client on your laptop** (so two clients can chat), a new PowerShell window:

```powershell
.\start_client.ps1
```

Each client should print `Connected to leader=<id>`. Type a line in one client; it appears in the
other. (A client only starts receiving after it has sent its first line, so type once in each.)
To override the display name, set `CLIENT_NAME` before running, but it is optional.

## 5. Failover test (the important one)

With both clients connected and chatting:

1. **Kill the leader**: on the host that logged `event=leader_elected` (the highest id), `Ctrl+C`
   the server (or `kill -9 <pid>`).
2. Watch the other server's log. Within ~6 s (heartbeat timeout) + ~2 s (election):

   ```
   event=peer_dead ... deadPeer=<old leader id>
   event=election_started myId=<this server's id> ...
   event=leader_elected myId=<this server's id>   # the next-highest survivor is the new leader
   ```
3. The clients print `Leader changed ... reconnecting` then `Connected to leader=<new id>`, and chat
   resumes. Anything typed during the gap is delivered after reconnect.
4. (Optional) Restart the killed server. It draws a **new** random id: if that id is higher than the
   current leader, it bullies its way back (the other logs `event=leader_rejected` then a new
   election); otherwise it rejoins as a replica.

## Troubleshooting

- **Servers never discover each other** - broadcast is blocked. Confirm same SSID (not eduroam,
  not "guest" Wi-Fi, which often isolates clients). Confirm the server runs on **native Windows, not
  WSL** (WSL2's NAT keeps its broadcast off the LAN). On Windows, make sure the firewall allows
  inbound UDP 45678 on **both** machines, and that no leftover BLOCK rule for `java.exe` overrides it.
  The server already announces to every interface's broadcast plus `255.255.255.255`, so a single
  wrong adapter is not the cause.
- **Client says "No leader found, retrying"** - the servers have no leader yet (still electing) or
  the client is on a different subnet. Check step 3 elected a leader, and that the client's IP
  shares the first three octets.
- **Client connects but no messages** - the receiving client must send one line first to register
  with the leader. Type once in each client.
- **Windows blocks it** - the firewall prompt was dismissed. Re-allow Java for Private networks, or
  add the firewall rules from step 3.
