# PLAN — IoT Device Management System (INSIDE M2M assignment)

Single source of truth for all implementation sessions. Each step below runs in its
own Claude Code conversation: start it with *"Read PLAN.md and do Step N"*.

## Context

- **Assignment** (Ilja Hellwich, Head of Embedded Development, 2026-10-02): build a small
  IoT device management system. Devices keep a persistent, bidirectional connection to a
  backend (devices → data/status, backend → commands). Must scale to many concurrent
  devices. Simple portal to see and control devices. Protocol, device shape and "many"
  are our choice. 1–2 days effort; what we leave out is part of the evaluation.
- **Interview: 2026-10-22.** Everything is built 2026-10-21; repo link goes out before the
  interview.
- **They will ask about:** demo · architecture + rejected alternatives · scaling (how many
  devices, how measured, what breaks first) · AI workflow (setup, prompts, rules, where AI
  was wrong and how we noticed) · **live pairing: we extend the system with a small feature**
  on the candidate's laptop.
- **Constraints:** public GitHub repo, English docs/code/commits, readable commit history
  showing the process. Candidate is basic–mid level in the stack → **every decision must be
  simple enough to defend**; prefer boring, explainable solutions over clever ones.

## Decisions (defend these — details go in `docs/decisions.md`)

| Topic | Choice | Rejected alternative & why |
|---|---|---|
| Stack | Angular + Java/Quarkus (their stack) | Node/Go — no reason to deviate |
| Device protocol | **WebSocket directly to Quarkus** (WebSockets Next), JSON text frames | MQTT + broker (EMQX): IoT standard and offloads connections, but adds infra and hides the interesting part; for a demo, owning the connections makes scaling behaviour visible and explainable. Mention it as the production path. Raw TCP: no browser/tooling support, own framing. HTTP polling: not a persistent bidirectional connection, command latency. |
| Liveness | Server auto-ping + last-seen; offline on close/timeout | MQTT Last Will — not available without a broker |
| Delivery | Own ack protocol: command has `commandId`, device replies `ack` → status `PENDING → SENT → ACKED / FAILED / TIMEOUT` | Fire-and-forget — no feedback to the operator |
| Persistence | PostgreSQL + Hibernate ORM Panache for **device registry + command log**; telemetry **in memory** (ring buffer, last N points per device) | Writing every telemetry message to Postgres = first bottleneck; production answer is a time-series DB (TimescaleDB/Influx) — documented, not built |
| Portal live updates | **SSE** from Quarkus, server-side batched (≤ 1 push/s with only changed devices) | WebSocket to browser — not needed, server→client only; pushing every telemetry message would flood the browser |
| Scale-out | **Single instance, measured.** Multi-instance design documented in an ADR (sticky LB + connection registry + Redis pub/sub to route commands to the node holding the socket) | Building it — not enough time; better one honest measurement than an unproven cluster |
| Simulator | Own Java app, JDK `java.net.http.WebSocket`, N virtual devices in one JVM | k6/artillery — can't easily emulate device behaviour (acks, interval changes) |

**Deliberately left out** (say so in README): auth/TLS for devices (ADR: per-device token in
handshake header, mTLS in production), multi-instance, Kubernetes, OTA firmware updates,
multi-tenancy, telemetry persistence, Grafana dashboards.

## Wire protocol (devices ↔ backend)

Endpoint: `ws://host:8080/ws/devices/{deviceId}` · all frames JSON text with a `type` field.

Device → server:
```json
{"type":"hello","deviceType":"thermostat","firmware":"1.0.0"}
{"type":"telemetry","ts":1761080000000,"data":{"temperature":21.4,"humidity":43}}
{"type":"ack","commandId":"<uuid>","status":"OK","message":"interval set to 5s"}
```
Server → device:
```json
{"type":"command","commandId":"<uuid>","name":"setInterval","params":{"seconds":5}}
```
Device types: `thermostat` (temperature, humidity, `setTargetTemp`) and `relay` (state on/off,
power draw, `setRelay`). Common commands: `reboot`, `setInterval`. Unknown command → ack
`ERROR`.

Exact payloads (as implemented by the simulator in Step 2):

| Type | Telemetry `data` | Command `name` → `params` |
|---|---|---|
| all | — | `reboot` → `{}` (ack, close, reconnect after 3 s) · `setInterval` → `{"seconds": 1..3600}` |
| `thermostat` | `{"temperature": 21.4, "humidity": 43.0, "targetTemp": 21.0}` | `setTargetTemp` → `{"target": 5..30}` |
| `relay` | `{"on": true, "power": 61.3}` | `setRelay` → `{"on": true\|false}` |

Invalid params → ack `ERROR` with a message saying what is expected. Keep command and device-type handling in one obvious place on each side — that is
where the live-pairing feature will most likely land.

## Repo layout

```
/backend     Quarkus app (Maven wrapper; Java 21 target)
/simulator   plain Java app (Maven wrapper), fat jar, CLI args
/portal      Angular app (standalone components, signals)
/docs        decisions.md (ADRs), scaling.md, architecture diagram (mermaid)
docker-compose.yml   postgres (+ backend/portal if time)
CLAUDE.md    rules for AI sessions
README.md
```
Ports: backend 8080, portal 4200 (dev proxy `/api` → 8080). Local toolchain: Java 27, Node 25,
Docker; no global Maven → use `./mvnw`.

## Steps

Priority: **M** = must for tomorrow, **S** = should, **C** = only if time is left.
Each step ends with: code builds/runs, commit(s) proposed
to the user (small, conventional-commit messages).

### Step 0 — Foundation (M, ~30 min)
- `git init`, `.gitignore`, repo layout, `CLAUDE.md` (rules: small verified steps, run what
  you build, no unexplained dependencies, every design choice → `docs/decisions.md`).
- Generate Quarkus backend (extensions: websockets-next, rest-jackson, hibernate-orm-panache,
  jdbc-postgresql, smallrye-health, micrometer-registry-prometheus). Use Dev Services for
  Postgres in dev/test; `docker-compose.yml` with Postgres for prod-like runs.
- Skeletons for `simulator/` and `portal/` (`ng new`, standalone, no SSR).
- Create public GitHub repo, push initial commit.
- Done when: `./mvnw quarkus:dev` starts, `ng serve` starts, repo is on GitHub.

### Step 1 — Backend: device connections & state (M)
- WebSockets Next endpoint `/ws/devices/{deviceId}`: `@OnOpen` registers connection in a
  `ConnectionRegistry` (ConcurrentHashMap deviceId → connection), `@OnClose` removes and marks
  offline, `@OnTextMessage` dispatches by `type`. Auto-ping configured.
- `hello` → upsert `Device` entity (id, type, firmware, firstSeen, lastSeen, online).
  Blocking DB work off the event loop (`@RunOnVirtualThread` or worker) — note why.
- `telemetry` → in-memory `DeviceState` (latest values + ring buffer of last 100 points);
  **no DB write per message**.
- REST: `GET /api/devices` (paged, filter by online/type), `GET /api/devices/{id}`
  (incl. latest telemetry + history), `GET /api/stats` (connected count, msgs/s).
- Micrometer gauges/counters: connected devices, messages received/s, commands sent/acked.
- Done when: a wscat/test client can connect, send hello + telemetry, and REST shows it.

### Step 2 — Simulator (M)
- CLI: `--url`, `--count N`, `--interval s`, `--ramp per-second`, `--prefix`, type mix.
- Each virtual device: connect, hello, periodic telemetry with plausible random-walk values,
  handles commands (`setInterval`, `reboot` = disconnect + reconnect after delay,
  `setRelay`, `setTargetTemp`), replies with `ack`; reconnect with backoff on drop.
- One JVM, scheduled executor (not one thread per device); prints connected/sent/acked stats.
- Done when: `--count 100` shows 100 online devices in the backend.

### Step 3 — Backend: command flow (M)
- `POST /api/devices/{id}/commands {name, params}` → persist `Command` (PENDING), send over
  socket (SENT) or fail immediately if device offline; on `ack` → ACKED/FAILED; timeout
  (e.g. 10 s, scheduled check) → TIMEOUT.
- `GET /api/devices/{id}/commands` history.
- Done when: a command sent via curl reaches a simulated device and shows ACKED.

### Step 4 — Backend: live stream for portal (M)
- `GET /api/stream` SSE: once per second emit batch of changed device summaries
  (dirty-set), plus stats and command status changes. Explain throttling in decisions.md.

### Step 5 — Portal (M)
- Device list: table with id, type, online badge, last seen, key value; filter (online/type),
  search; scales to thousands rows (paging or CDK virtual scroll). Header: online/total
  counts, msgs/s.
- Device detail: latest values, small live chart of the history (lightweight — plain SVG
  sparkline or a small lib, justify it), command panel with type-specific commands, command
  history with live status.
- Signals-based store fed by REST + SSE; clean component split (list, detail, command panel,
  services). Keep it plain, no UI framework beyond what's needed (Angular Material optional).
- Done when: full demo works: start simulator → devices appear live → send command → ACKED.

### Step 6 — Load test & scaling numbers (M — they explicitly ask)
- Ramp simulator 1k → 5k → 10k → … until something breaks; record per level: connected
  devices, msgs/s, backend CPU/heap (`/q/metrics`, JVM), command round-trip latency, portal
  responsiveness.
- Expected breakers to verify (not assume): client-side ephemeral ports (~16k on macOS per
  src/dst pair → use multiple simulator processes / loopback aliases), `ulimit -n`, heap per
  connection, connect storms hitting the DB (upsert on hello), SSE batch size.
- Write `docs/scaling.md`: method, table of results, what broke first and why, how to go
  beyond (multi-instance ADR, MQTT broker, time-series DB).

### Step 7 — Tests (S)
- `@QuarkusTest`: WebSocket client connects as device → hello → telemetry visible via REST;
  command → device receives → ack → ACKED; offline device → command FAILED.
- One Playwright E2E: portal shows a simulated device and a command reaches ACKED.

### Step 8 — Docs, polish, deliver (M)
- `README.md`: what it is, 3-command quickstart, architecture diagram (mermaid), key decisions
  (link ADRs), scaling summary, **AI workflow section** (setup: Claude Code + CLAUDE.md +
  plan-per-session; prompts/rules; concrete cases where AI was wrong — provided by the user),
  deliberately left out, possible next features.
- Final push; send repo link to Ilja.

### Stretch (C)
- docker-compose for full stack · Grafana dashboard · multi-instance demo (HAProxy + 2
  Quarkus + Redis pub/sub) · device auth token.

## Session log

| Step | Status | Notes |
|---|---|---|
| Plan | done | protocol: WebSocket direct to Quarkus (user decision) |
| 0 | done | Repo: https://github.com/mnkasikci/iot-device-management (public). Quarkus 3.40.1.1, Angular 22.2.3. Colima needs `DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` (see CLAUDE.md). Compose Postgres on host port **5433** (local PG on 5432); `docker compose` not installed here, verified via `docker run`. |
| 1 | done | `/ws/devices/{id}` + `ConnectionRegistry` (newest socket wins), sealed `DeviceMessage` switch in `DeviceSocket.onMessage` = the one place for message types. Callbacks on worker threads (ADR-011). `LivenessCheck` closes sockets silent > 90 s (no pong-timeout setting in WebSockets Next; ADR-003). REST `/api/devices`, `/api/devices/{id}`, `/api/stats`; metrics `devices_*` at `/q/metrics`. Added `quarkus-scheduler`, `rest-assured` (test). Verified: 3 `@QuarkusTest`s green + manual JDK-WebSocket client against dev mode (filters, paging, 404, ring buffer keeps last 100, silent client closed after timeout + 10 s Vert.x close timeout). |
| 2 | done | `simulator/`: `VirtualDevice` (connection, `handleCommand` = one place for commands), `DeviceModel` + `Thermostat`/`Relay` (one place for device types: `DeviceModel.create`). Payloads now in the wire-protocol table above. Added `jackson-databind`, `maven-shade-plugin`. Verified: `--count 100` → backend shows 100 online, 50/50 types, 50 msgs/s; commands/acks/reboot/abrupt drop/backoff checked against a throwaway raw-socket WS server (backend has no command endpoint until Step 3); backend restart → all 100 back within ~30 s, spread by jitter. |
| 3 | done | `command` package: `Command` entity (params as `jsonb`), `CommandService` = the one place for the flow (send / `onAck` / `@Scheduled` timeout), `CommandResource` `POST`/`GET /api/devices/{id}/commands` (202 + current status). Backend checks only device exists + name (ADR-013); status changes = short transactions + conditional updates, SENT committed before the frame goes out (ADR-014). Timeout `commands.timeout=10s`, checked every 2 s (test profile: 2 s / 500 ms). Needed `quarkus.hibernate-orm.mapping.format.global=ignore` for the jsonb column. Verified: 8 `@QuarkusTest`s green (ACKED, ERROR→FAILED, offline→FAILED, TIMEOUT, 404/400) + curl against dev mode with the real simulator: OK→ACKED, invalid target / unknown command→FAILED with device message, reboot ACKED + offline→online in ~3 s, simulator stopped→FAILED "device offline", stub device never acking→TIMEOUT after ~11.5 s; `devices_commands_sent/acked_total` match. |
| 4 | done | `stream/LiveUpdates`: dirty sets (`deviceChanged`/`commandChanged`, marked in `DeviceSocket` on hello/telemetry/close and in `CommandService` on every status change) drained by a 1 s `@Scheduled` tick into one `Batch {ts, stats, devices, commands}`, fanned out via Mutiny `BroadcastProcessor` (`onOverflow().drop()` for slow browsers). `GET /api/stream` = `StreamResource` (SSE, JSON). `DeviceSummary` moved to `device/` (shared by REST + stream); timeout job now selects ids first so it can mark them. ADR-006 consequences. Verified: `LiveStreamTest` + all 9 tests green; curl against dev mode with 20 simulated devices (2 s interval): 1 batch/s with ~10 changed devices, command ACKED in next batch, reboot shows online=false then true ~3 s later. |
