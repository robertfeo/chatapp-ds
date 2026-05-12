# Team Implementation Plan · Team-Implementierungsplan

**Language · Sprache:** [English](#english) · [Deutsch](#deutsch)

---

## English

Three-week implementation, three GitHub milestones aligned with the course practical sessions. Every issue on the [issues board](https://github.com/robertfeo/chatapp-ds/issues) is already labelled with an area (`area:*`) and assigned to a milestone. This document maps people to issues so nobody asks "what should I work on?".

### Quick reference

| Milestone | Window | Theme |
|---|---|---|
| **M1 — Foundations & Discovery** | 2026-05-12 → 2026-05-19 | Repo + protocol + UDP broadcast discovery |
| **M2 — Heartbeats, Election & Failover** | 2026-05-19 → 2026-05-26 | Heartbeats, highest-ID-wins, automatic leader change |
| **M3 — Polish, Demo & Report** | 2026-05-26 → 2026-06-02 | Feature freeze 2026-05-30, demo on 3 physical hosts, report |

**Demo target:** Robert's Raspberry Pi 4 (default leader, highest ID) + 2 student laptops on a private LAN. No Docker, no VMs. **Avoid eduroam** — it blocks UDP broadcast. Use a phone hotspot or a home Wi-Fi router.

**Stack:** Java 21 LTS, Maven, Jackson (JSON), SLF4J + Logback (logging), JUnit 5 (tests). Java stdlib networking only (`java.net.DatagramSocket`, `java.net.Socket`, `java.util.concurrent`). No middleware.

**Written exam (separate from project):** 2026-07-16, 09:00–12:00, examiner Christian Decker.

### Role overview

| Person | Role | Primary areas |
|---|---|---|
| **Robert** | Tech lead, integration owner | Server core, protocol, election, heartbeats, CI, demo orchestration |
| **Ayham** | Discovery & client lead | UDP broadcast discovery, client app, state replication, client reconnect |
| **Samet** | Docs, diagrams & demo lead | Architecture diagram, README, Project Report Form, demo slides, demo coordination |

Everyone reviews PRs in their area. The demo dry run is a whole-team activity.

---

### Robert-Bogdan Fesko — Tech Lead

#### Responsibilities
- Owns the server core, the wire protocol, and the leader-election logic.
- Maintains the Maven build, CI, and the local dev runner.
- Sets up GitHub Actions and keeps `main` green.
- Reviews every PR (alongside the area owner) before merge.
- Owns the multi-host demo runbook and the feature freeze.
- Writes the integration test for the failover scenario.

#### Issues by milestone

**M1**
- #1 — Repo skeleton: Maven Java 21 project layout, `pom.xml`
- #2 — Local dev runner: Makefile spawning 3 server processes + 2 clients on localhost
- #3 — GitHub Actions CI: Maven build + Spotless + JUnit on every push
- #4 — Define wire protocol: JSON envelopes for discovery, heartbeat, chat, state-sync
- #5 — Server bootstrap: load unique numeric ID + ports from env, structured logging

**M2**
- #9 — Heartbeat protocol: 2 s interval, 6 s peer-dead timeout
- #10 — Highest-ID-Wins leader election, triggered on leader-timeout
- #14 — Unit tests: election algorithm
- #15 — Integration test: kill leader mid-chat, verify new leader takes over

**M3**
- #19 — Feature freeze on 2026-05-30; bug fixes only after
- #20 — Multi-host runbook for demo: Pi-as-leader bootstrap, laptops join as replicas
- #21 — Edge cases: simultaneous boot, heartbeat jitter, late-joining replica

#### Notes
- Sequence M1 issues so #4 (protocol) lands before #5 (server bootstrap) and #6 (Ayham's discovery work). Ayham is blocked on the protocol envelope until #4 is merged.
- Coordinate with Ayham at the M1/M2 boundary on the `Message` sealed-interface API — both of you import it.
- The Pi 4 install: `sudo apt install openjdk-21-jre-headless` on Raspberry Pi OS Bookworm.

---

### Ayham Alhasan — Discovery & Client Lead

#### Responsibilities
- Owns dynamic discovery via UDP broadcast on the server side.
- Owns the client application end-to-end: discovery, TCP connect, send/receive chat, reconnect on leader loss.
- Owns replica state sync (leader → replicas) and the rejoin-with-history path.
- Reviews PRs in the discovery and client areas.

#### Issues by milestone

**M1**
- #6 — UDP broadcast discovery: server-side announce & listen, build local group view
- #7 — Client: discover leader via broadcast, connect over TCP, send/receive chat

**M2**
- #11 — Leader announcement (`I_AM_LEADER`) and replica state update
- #12 — Replica state sync: leader pushes chat history delta to replicas
- #13 — Client reconnect on leader-loss: re-discover and connect to new leader

#### Notes
- Consume the protocol envelope from #4. If you need a field that isn't in the envelope, raise it on #4 before #4 is merged.
- For the localhost dev setup, all three server processes share the discovery port — use `DatagramSocket.setReuseAddress(true)` so they can co-exist. On real LAN hosts this is no longer required but the code path stays identical.
- Test client reconnect with the integration test from #15 — coordinate with Robert so the test sees both your reconnect logic and his election logic working together.

---

### Samet Yilmaz — Docs, Diagrams & Demo Lead

You own two of the graded artifacts: the **Project Report Form** and the **live demo**. Together that's a large fraction of the project grade. Your work is not auxiliary — it's the visible face of everything the team builds.

#### Responsibilities
- Owns the architecture diagram from first sketch to the version embedded in the report and the slides.
- Owns the README so anyone reading the repo for the first time understands the system without reading the code.
- Owns the Manual Test Plan — the checklist the team follows during every dry run and during the live demo.
- Owns the **Project Report Form**: writes all five sections, gets Robert's technical review, exports the non-editable PDF, uploads to RELAX.
- Owns the **demo slides** and the **cue sheet**: who speaks, when, what gets typed on stage, when to kill the leader.
- Drives the dry runs: schedules them, times them, files an issue for every rough edge found.

#### Issues by milestone

**M1**
- #8 — Architecture diagram v1 (PlantUML source under `/docs`)

**M2**
- #17 — README update: discovery, election, heartbeat protocol descriptions
- #18 — Manual test plan v1: numbered demo scenarios

**M3**
- #22 — Project Report Form: fill all 5 sections, export non-editable PDF
- #23 — Final architecture diagram: cleaned-up PlantUML, embedded in report
- #24 — Demo slides + cue sheet (~5 slides, English)

#### How to contribute (practical notes)
- The architecture diagram lives as a PlantUML source file `docs/architecture.puml`. Robert can scaffold the first version of that file with the components and arrows already in place — you iterate on labels, layout, colors, and export the PNG.
  - **Render options:** the [PlantUML online editor](https://www.plantuml.com/plantuml/uml) (paste the source, download PNG), or the VS Code extension "PlantUML" (preview + export).
- All documents in this folder are plain Markdown (`.md`). Edit in any text editor or VS Code; preview side-by-side with a Markdown extension.
- For the Project Report Form, draft in `docs/project_report.md` first so the team can review the wording, then transfer into the official PDF form template from ILIAS/RELAX and export non-editable.
- For commits and PRs: pair with Robert the first time. He can walk you through `git commit` and `git push`, or commit your docs on your behalf from a shared call.

#### Notes
- The reading order matters: do **#18 (manual test plan)** in M2 before the team starts writing #15 (integration test) — your scenarios shape what the integration test should cover.
- The Project Report Form is due before the demo; the demo schedule isn't announced yet but the form text should be finalised by **2026-05-29** at the latest, so it doesn't compete for time with the dry run.

---

### Shared work

| Issue | When | Who |
|---|---|---|
| #16 — Validate UDP broadcast on the demo LAN early (Pi 4 + 2 laptops) | M2 — earliest feasible date | Whoever has all three hosts at hand (likely Robert + 1 other). Result is a blocker for M3. |
| #25 — Multi-host dry run: full team on a call, demo end-to-end | Twice during M3 | Everyone present, Samet runs the cue sheet, Robert kills the leader on cue |

---

### Workflow

#### Branching and PRs
- Feature branches off `main`. Naming: `feat/<area>-<short>`, `fix/<area>-<short>`, `docs/<short>`, `chore/<short>`.
- One PR per logical change. CI (`mvn -B verify`) must pass before merge.
- Reviewer: at least one other team member; Robert reviews all technical PRs.
- Squash-merge into `main`.

#### Commits
- Conventional Commits style: `feat:`, `fix:`, `chore:`, `docs:`, `test:`.
- One logical change per commit. Keep messages in English.

#### Definition of done (per issue)
- Code merged into `main` (or doc artifact present in `docs/`).
- CI green.
- Acceptance criteria from the issue body satisfied.
- For features: a unit or integration test exists.
- For docs: linked from the README or the report draft.

#### Communication
- All task tracking on GitHub Issues. If it isn't filed as an issue, it doesn't exist.
- Sync channel: TBD (Discord / WhatsApp group). Async questions go on the relevant issue's thread for the record.

#### Project language
- Every artifact in this repo and on GitHub is in English: code, comments, commit messages, branch names, PR bodies, issue titles, labels, milestones, the README, the report, the slides, the diagram labels.
- This team plan document is the one exception: bilingual English/German so every team member can read in their preferred language.

[↑ Back to top](#team-implementation-plan--team-implementierungsplan)

---

## Deutsch

Eine dreiwöchige Implementierungsphase, drei GitHub-Milestones, abgestimmt auf die praktischen Kurseinheiten. Jedes Issue im [Issues-Board](https://github.com/robertfeo/chatapp-ds/issues) ist bereits mit einem Bereichs-Label (`area:*`) versehen und einem Milestone zugewiesen. Dieses Dokument ordnet Personen zu Issues zu, damit niemand fragen muss: „Woran soll ich arbeiten?".

### Kurzübersicht

| Milestone | Zeitraum | Schwerpunkt |
|---|---|---|
| **M1 — Grundlagen & Discovery** | 2026-05-12 → 2026-05-19 | Repository + Protokoll + UDP-Broadcast-Discovery |
| **M2 — Heartbeats, Wahl & Failover** | 2026-05-19 → 2026-05-26 | Heartbeats, Highest-ID-Wins, automatischer Anführerwechsel |
| **M3 — Feinschliff, Demo & Bericht** | 2026-05-26 → 2026-06-02 | Feature-Freeze am 2026-05-30, Demo auf 3 physischen Hosts, Bericht |

**Demo-Ziel:** Roberts Raspberry Pi 4 (Standard-Leader, höchste ID) + 2 Studenten-Laptops in einem privaten LAN. Kein Docker, keine VMs. **Eduroam vermeiden** — UDP-Broadcasts werden dort blockiert. Stattdessen einen Handy-Hotspot oder einen heimischen WLAN-Router verwenden.

**Tech-Stack:** Java 21 LTS, Maven, Jackson (JSON), SLF4J + Logback (Logging), JUnit 5 (Tests). Ausschließlich Java-Standardbibliothek für Networking (`java.net.DatagramSocket`, `java.net.Socket`, `java.util.concurrent`). Keine Middleware.

**Schriftliche Prüfung (vom Projekt getrennt):** 2026-07-16, 09:00–12:00 Uhr, Prüfer Christian Decker.

### Rollenübersicht

| Person | Rolle | Hauptbereiche |
|---|---|---|
| **Robert** | Technischer Leiter, Integrationsverantwortlicher | Server-Kern, Protokoll, Wahl, Heartbeats, CI, Demo-Orchestrierung |
| **Ayham** | Discovery- und Client-Verantwortlicher | UDP-Broadcast-Discovery, Client-App, State-Replikation, Client-Reconnect |
| **Samet** | Dokumentations-, Diagramm- und Demo-Verantwortlicher | Architekturdiagramm, README, Project Report Form, Demo-Folien, Demo-Koordination |

Alle reviewen PRs in ihrem Bereich. Die Demo-Generalprobe ist eine Aufgabe für das gesamte Team.

---

### Robert-Bogdan Fesko — Technischer Leiter

#### Aufgaben
- Verantwortlich für den Server-Kern, das Wire-Protokoll und die Leader-Election-Logik.
- Pflegt den Maven-Build, die CI und den lokalen Dev-Runner.
- Richtet GitHub Actions ein und hält `main` grün.
- Reviewt jeden PR (gemeinsam mit dem jeweiligen Bereichsverantwortlichen) vor dem Merge.
- Verantwortlich für das Multi-Host-Demo-Runbook und den Feature-Freeze.
- Schreibt den Integration-Test für das Failover-Szenario.

#### Issues nach Milestone

**M1**
- #1 — Repo-Skeleton: Maven-Java-21-Projektstruktur, `pom.xml`
- #2 — Lokaler Dev-Runner: Makefile, das 3 Server-Prozesse + 2 Clients auf localhost startet
- #3 — GitHub-Actions-CI: Maven-Build + Spotless + JUnit bei jedem Push
- #4 — Wire-Protokoll definieren: JSON-Envelopes für Discovery, Heartbeat, Chat, State-Sync
- #5 — Server-Bootstrap: eindeutige numerische ID + Ports aus Env laden, strukturiertes Logging

**M2**
- #9 — Heartbeat-Protokoll: 2 s Intervall, 6 s Peer-Dead-Timeout
- #10 — Highest-ID-Wins-Leader-Election, ausgelöst beim Leader-Timeout
- #14 — Unit-Tests: Wahl-Algorithmus
- #15 — Integration-Test: Leader mitten im Chat töten, prüfen, dass neuer Leader übernimmt

**M3**
- #19 — Feature-Freeze am 2026-05-30; danach nur noch Bugfixes
- #20 — Multi-Host-Runbook für die Demo: Pi-als-Leader-Bootstrap, Laptops treten als Replikate bei
- #21 — Edge Cases: gleichzeitiger Boot, Heartbeat-Jitter, spät beitretendes Replikat

#### Hinweise
- M1-Issues so sequenzieren, dass #4 (Protokoll) vor #5 (Server-Bootstrap) und #6 (Ayhams Discovery-Arbeit) gemerged wird. Ayham ist beim Protokoll-Envelope blockiert, bis #4 gemerged ist.
- An der M1/M2-Grenze mit Ayham bezüglich der `Message`-Sealed-Interface-API abstimmen — ihr beide importiert sie.
- Pi-4-Installation: `sudo apt install openjdk-21-jre-headless` unter Raspberry Pi OS Bookworm.

---

### Ayham Alhasan — Discovery- und Client-Verantwortlicher

#### Aufgaben
- Verantwortlich für dynamische Discovery via UDP-Broadcast auf Server-Seite.
- Verantwortlich für die Client-Anwendung Ende-zu-Ende: Discovery, TCP-Connect, Chat senden/empfangen, Reconnect bei Leader-Verlust.
- Verantwortlich für State-Replikation (Leader → Replikate) und den Rejoin-mit-Historie-Pfad.
- Reviewt PRs in den Bereichen Discovery und Client.

#### Issues nach Milestone

**M1**
- #6 — UDP-Broadcast-Discovery: serverseitiges Announce & Listen, lokale Group-View aufbauen
- #7 — Client: Leader via Broadcast entdecken, über TCP verbinden, Chat senden/empfangen

**M2**
- #11 — Leader-Ankündigung (`I_AM_LEADER`) und Replikat-State-Update
- #12 — Replikat-State-Sync: Leader pusht Chat-Historien-Deltas zu Replikaten
- #13 — Client-Reconnect bei Leader-Verlust: erneute Discovery und Verbindung zum neuen Leader

#### Hinweise
- Den Protokoll-Envelope aus #4 verwenden. Falls ein Feld fehlt, vor dem Merge von #4 in #4 melden.
- Im localhost-Dev-Setup teilen sich alle drei Server-Prozesse den Discovery-Port — `DatagramSocket.setReuseAddress(true)` verwenden, damit sie koexistieren können. Auf realen LAN-Hosts ist das nicht mehr nötig, der Code-Pfad bleibt aber identisch.
- Client-Reconnect mit dem Integration-Test aus #15 testen — mit Robert abstimmen, damit der Test sowohl deine Reconnect-Logik als auch seine Wahl-Logik gemeinsam abdeckt.

---

### Samet Yilmaz — Dokumentations-, Diagramm- und Demo-Verantwortlicher

Du bist verantwortlich für zwei der bewerteten Artefakte: die **Project Report Form** und die **Live-Demo**. Zusammen ergibt das einen großen Teil der Projektnote. Deine Arbeit ist nicht „Nebenarbeit" — sie ist das sichtbare Gesicht von allem, was das Team baut.

#### Aufgaben
- Verantwortlich für das Architekturdiagramm vom ersten Entwurf bis zur Version, die im Bericht und in den Folien eingebettet ist.
- Verantwortlich für das README, damit jede:r, der/die das Repo zum ersten Mal liest, das System versteht, ohne den Code lesen zu müssen.
- Verantwortlich für den Manual Test Plan — die Checkliste, die das Team bei jeder Generalprobe und während der Live-Demo abarbeitet.
- Verantwortlich für die **Project Report Form**: schreibt alle fünf Abschnitte, holt Roberts technische Review ein, exportiert das nicht-editierbare PDF, lädt es in RELAX hoch.
- Verantwortlich für die **Demo-Folien** und das **Cue Sheet**: wer wann spricht, was auf der Bühne getippt wird, wann der Leader getötet wird.
- Treibt die Generalproben: plant sie, misst die Zeit, eröffnet ein Issue für jede Schwachstelle.

#### Issues nach Milestone

**M1**
- #8 — Architekturdiagramm v1 (PlantUML-Quelle unter `/docs`)

**M2**
- #17 — README-Update: Discovery, Wahl, Heartbeat-Protokollbeschreibungen
- #18 — Manual Test Plan v1: nummerierte Demo-Szenarien

**M3**
- #22 — Project Report Form: alle 5 Abschnitte ausfüllen, nicht-editierbares PDF exportieren
- #23 — Finales Architekturdiagramm: bereinigtes PlantUML, im Bericht eingebettet
- #24 — Demo-Folien + Cue Sheet (~5 Folien, Englisch)

#### Wie du beitragen kannst (praktische Hinweise)
- Das Architekturdiagramm liegt als PlantUML-Quelldatei `docs/architecture.puml` vor. Robert kann die erste Version dieser Datei mit Komponenten und Pfeilen bereits skizzieren — du iterierst über Beschriftungen, Layout, Farben und exportierst die PNG.
  - **Render-Optionen:** der [PlantUML-Online-Editor](https://www.plantuml.com/plantuml/uml) (Quelle einfügen, PNG herunterladen) oder die VS-Code-Extension „PlantUML" (Vorschau + Export).
- Alle Dokumente in diesem Ordner sind reines Markdown (`.md`). Bearbeiten in beliebigem Texteditor oder VS Code; Seite-an-Seite-Vorschau mit einer Markdown-Extension.
- Für die Project Report Form: zuerst in `docs/project_report.md` einen Entwurf schreiben, damit das Team den Wortlaut reviewen kann, danach in das offizielle PDF-Form-Template aus ILIAS/RELAX übertragen und nicht-editierbar exportieren.
- Für Commits und PRs: beim ersten Mal mit Robert zusammenarbeiten. Er kann dich durch `git commit` und `git push` führen oder deine Doku-Änderungen in einem gemeinsamen Call in deinem Namen committen.

#### Hinweise
- Die Reihenfolge ist wichtig: erledige **#18 (Manual Test Plan)** in M2, bevor das Team #15 (Integration-Test) schreibt — deine Szenarien prägen, was der Integration-Test abdecken soll.
- Die Project Report Form ist vor der Demo fällig; der Demo-Termin steht noch nicht fest, aber der Form-Text sollte spätestens am **2026-05-29** finalisiert sein, damit er nicht mit der Generalprobe um Zeit konkurriert.

---

### Gemeinsame Arbeit

| Issue | Wann | Wer |
|---|---|---|
| #16 — UDP-Broadcast frühzeitig im Demo-LAN validieren (Pi 4 + 2 Laptops) | M2 — frühestmöglicher Termin | Wer alle drei Hosts zur Hand hat (vermutlich Robert + 1 weitere Person). Das Ergebnis blockiert M3. |
| #25 — Multi-Host-Generalprobe: gesamtes Team in einem Call, Demo Ende-zu-Ende | Zweimal während M3 | Alle anwesend, Samet führt das Cue Sheet, Robert tötet den Leader auf Stichwort |

---

### Arbeitsablauf

#### Branching und PRs
- Feature-Branches von `main`. Naming: `feat/<bereich>-<kurz>`, `fix/<bereich>-<kurz>`, `docs/<kurz>`, `chore/<kurz>`.
- Ein PR pro logischer Änderung. CI (`mvn -B verify`) muss vor dem Merge grün sein.
- Reviewer: mindestens ein anderes Teammitglied; Robert reviewt alle technischen PRs.
- Squash-Merge nach `main`.

#### Commits
- Conventional-Commits-Stil: `feat:`, `fix:`, `chore:`, `docs:`, `test:`.
- Eine logische Änderung pro Commit. Nachrichten auf Englisch.

#### Definition of Done (pro Issue)
- Code in `main` gemerged (oder Doku-Artefakt in `docs/` vorhanden).
- CI grün.
- Akzeptanzkriterien aus dem Issue-Body erfüllt.
- Für Features: ein Unit- oder Integration-Test existiert.
- Für Doku: aus dem README oder dem Bericht-Entwurf verlinkt.

#### Kommunikation
- Sämtliches Task-Tracking auf GitHub Issues. Was nicht als Issue erfasst ist, existiert nicht.
- Sync-Kanal: noch festzulegen (Discord / WhatsApp-Gruppe). Asynchrone Fragen kommen in den Thread des betreffenden Issues, damit sie nachvollziehbar bleiben.

#### Projektsprache
- Jedes Artefakt in diesem Repository und auf GitHub ist auf Englisch: Code, Kommentare, Commit-Messages, Branch-Namen, PR-Bodies, Issue-Titel, Labels, Milestones, das README, der Bericht, die Folien, die Diagramm-Beschriftungen.
- Dieses Team-Plan-Dokument ist die einzige Ausnahme: bilingual Englisch/Deutsch, damit jedes Teammitglied in seiner bevorzugten Sprache lesen kann.

[↑ Zurück nach oben](#team-implementation-plan--team-implementierungsplan)
