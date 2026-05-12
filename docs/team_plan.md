# Team Implementation Plan — chatapp-ds, Group 16, SS 26

Three-week implementation, three GitHub milestones aligned with the course practical sessions. Every issue on the [issues board](https://github.com/robertfeo/chatapp-ds/issues) is already labelled with an area (`area:*`) and assigned to a milestone. This document maps people to issues so nobody asks "what should I work on?".

## Quick reference

| Milestone | Window | Theme |
|---|---|---|
| **M1 — Foundations & Discovery** | 2026-05-12 → 2026-05-19 | Repo + protocol + UDP broadcast discovery |
| **M2 — Heartbeats, Election & Failover** | 2026-05-19 → 2026-05-26 | Heartbeats, highest-ID-wins, automatic leader change |
| **M3 — Polish, Demo & Report** | 2026-05-26 → 2026-06-02 | Feature freeze 2026-05-30, demo on 3 physical hosts, report |

**Demo target:** Robert's Raspberry Pi 4 (default leader, highest ID) + 2 student laptops on a private LAN. No Docker, no VMs. **Avoid eduroam** — it blocks UDP broadcast. Use a phone hotspot or a home Wi-Fi router.

**Stack:** Java 21 LTS, Maven, Jackson (JSON), SLF4J + Logback (logging), JUnit 5 (tests). Java stdlib networking only (`java.net.DatagramSocket`, `java.net.Socket`, `java.util.concurrent`). No middleware.

**Written exam (separate from project):** 2026-07-16, 09:00–12:00, examiner Christian Decker.

## Role overview

| Person | Role | Primary areas |
|---|---|---|
| **Robert** | Tech lead, integration owner | Server core, protocol, election, heartbeats, CI, demo orchestration |
| **Ayham** | Discovery & client lead | UDP broadcast discovery, client app, state replication, client reconnect |
| **Samet** | Docs, diagrams & demo lead | Architecture diagram, README, Project Report Form, demo slides, demo coordination |

Everyone reviews PRs in their area. The demo dry run is a whole-team activity.

---

## Robert-Bogdan Fesko — Tech Lead

### Responsibilities
- Owns the server core, the wire protocol, and the leader-election logic.
- Maintains the Maven build, CI, and the local dev runner.
- Sets up GitHub Actions and keeps `main` green.
- Reviews every PR (alongside the area owner) before merge.
- Owns the multi-host demo runbook and the feature freeze.
- Writes the integration test for the failover scenario.

### Issues by milestone

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

### Notes
- Sequence M1 issues so #4 (protocol) lands before #5 (server bootstrap) and #6 (Ayham's discovery work). Ayham is blocked on the protocol envelope until #4 is merged.
- Coordinate with Ayham at the M1/M2 boundary on the `Message` sealed-interface API — both of you import it.
- The Pi 4 install: `sudo apt install openjdk-21-jre-headless` on Raspberry Pi OS Bookworm.

---

## Ayham Alhasan — Discovery & Client Lead

### Responsibilities
- Owns dynamic discovery via UDP broadcast on the server side.
- Owns the client application end-to-end: discovery, TCP connect, send/receive chat, reconnect on leader loss.
- Owns replica state sync (leader → replicas) and the rejoin-with-history path.
- Reviews PRs in the discovery and client areas.

### Issues by milestone

**M1**
- #6 — UDP broadcast discovery: server-side announce & listen, build local group view
- #7 — Client: discover leader via broadcast, connect over TCP, send/receive chat

**M2**
- #11 — Leader announcement (`I_AM_LEADER`) and replica state update
- #12 — Replica state sync: leader pushes chat history delta to replicas
- #13 — Client reconnect on leader-loss: re-discover and connect to new leader

### Notes
- Consume the protocol envelope from #4. If you need a field that isn't in the envelope, raise it on #4 before #4 is merged.
- For the localhost dev setup, all three server processes share the discovery port — use `DatagramSocket.setReuseAddress(true)` so they can co-exist. On real LAN hosts this is no longer required but the code path stays identical.
- Test client reconnect with the integration test from #15 — coordinate with Robert so the test sees both your reconnect logic and his election logic working together.

---

## Samet Yilmaz — Docs, Diagrams & Demo Lead

You own two of the graded artifacts: the **Project Report Form** and the **live demo**. Together that's a large fraction of the project grade. Your work is not auxiliary — it's the visible face of everything the team builds.

### Responsibilities
- Owns the architecture diagram from first sketch to the version embedded in the report and the slides.
- Owns the README so anyone reading the repo for the first time understands the system without reading the code.
- Owns the Manual Test Plan — the checklist the team follows during every dry run and during the live demo.
- Owns the **Project Report Form**: writes all five sections, gets Robert's technical review, exports the non-editable PDF, uploads to RELAX.
- Owns the **demo slides** and the **cue sheet**: who speaks, when, what gets typed on stage, when to kill the leader.
- Drives the dry runs: schedules them, times them, files an issue for every rough edge found.

### Issues by milestone

**M1**
- #8 — Architecture diagram v1 (PlantUML source under `/docs`)

**M2**
- #17 — README update: discovery, election, heartbeat protocol descriptions
- #18 — Manual test plan v1: numbered demo scenarios

**M3**
- #22 — Project Report Form: fill all 5 sections, export non-editable PDF
- #23 — Final architecture diagram: cleaned-up PlantUML, embedded in report
- #24 — Demo slides + cue sheet (~5 slides, English)

### How to contribute (practical notes)
- The architecture diagram lives as a PlantUML source file `docs/architecture.puml`. Robert can scaffold the first version of that file with the components and arrows already in place — you iterate on labels, layout, colors, and export the PNG.
  - **Render options:** the [PlantUML online editor](https://www.plantuml.com/plantuml/uml) (paste the source, download PNG), or the VS Code extension "PlantUML" (preview + export).
- All documents in this folder are plain Markdown (`.md`). Edit in any text editor or VS Code; preview side-by-side with a Markdown extension.
- For the Project Report Form, draft in `docs/project_report.md` first so the team can review the wording, then transfer into the official PDF form template from ILIAS/RELAX and export non-editable.
- For commits and PRs: pair with Robert the first time. He can walk you through `git commit` and `git push`, or commit your docs on your behalf from a shared call.

### Notes
- The reading order matters: do **#18 (manual test plan)** in M2 before the team starts writing #15 (integration test) — your scenarios shape what the integration test should cover.
- The Project Report Form is due before the demo; the demo schedule isn't announced yet but the form text should be finalised by **2026-05-29** at the latest, so it doesn't compete for time with the dry run.

---

## Shared work

| Issue | When | Who |
|---|---|---|
| #16 — Validate UDP broadcast on the demo LAN early (Pi 4 + 2 laptops) | M2 — earliest feasible date | Whoever has all three hosts at hand (likely Robert + 1 other). Result is a blocker for M3. |
| #25 — Multi-host dry run: full team on a call, demo end-to-end | Twice during M3 | Everyone present, Samet runs the cue sheet, Robert kills the leader on cue |

---

## Workflow

### Branching and PRs
- Feature branches off `main`. Naming: `feat/<area>-<short>`, `fix/<area>-<short>`, `docs/<short>`, `chore/<short>`.
- One PR per logical change. CI (`mvn -B verify`) must pass before merge.
- Reviewer: at least one other team member; Robert reviews all technical PRs.
- Squash-merge into `main`.

### Commits
- Conventional Commits style: `feat:`, `fix:`, `chore:`, `docs:`, `test:`.
- One logical change per commit. Keep messages in English.

### Definition of done (per issue)
- Code merged into `main` (or doc artifact present in `docs/`).
- CI green.
- Acceptance criteria from the issue body satisfied.
- For features: a unit or integration test exists.
- For docs: linked from the README or the report draft.

### Communication
- All task tracking on GitHub Issues. If it isn't filed as an issue, it doesn't exist.
- Sync channel: TBD (Discord / WhatsApp group). Async questions go on the relevant issue's thread for the record.

### Project language
- Every artifact in this repo and on GitHub is in English: code, comments, commit messages, branch names, PR bodies, issue titles, labels, milestones, the README, the report, the slides, the diagram labels.
