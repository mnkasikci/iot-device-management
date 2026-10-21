# Architecture decisions

Short ADRs. Each one: context → decision → rejected alternatives → consequences.
Seeded from the decisions table in `PLAN.md`; filled in as the implementation steps land.
Status: *Accepted* = decided and (being) built, *Proposed* = documented only, not built.

---

## ADR-001 — Stack: Angular + Java/Quarkus

- **Status:** Accepted
- **Context:** The team works with Angular and Java/Quarkus.
- **Decision:** Backend in Quarkus, portal in Angular.
- **Rejected:** Node.js / Go backend — no reason to deviate from the team's stack.
- **Consequences:** TODO

## ADR-002 — Device protocol: WebSocket directly to Quarkus

- **Status:** Accepted
- **Context:** Devices need a persistent, bidirectional connection (data/status up,
  commands down) and the system must handle many concurrent devices.
- **Decision:** Devices connect via WebSocket to `ws://host:8080/ws/devices/{deviceId}`
  (Quarkus WebSockets Next), JSON text frames with a `type` field.
- **Rejected:**
  - MQTT + broker (e.g. EMQX): the IoT standard and offloads connection handling, but adds
    infrastructure and hides the interesting part. For a demo, owning the connections makes
    scaling behaviour visible and explainable. → the production path.
  - Raw TCP: no browser/tooling support, own framing.
  - HTTP polling: not a persistent bidirectional connection, command latency.
- **Consequences:** TODO

## ADR-003 — Liveness: server ping + last-seen

- **Status:** Accepted
- **Context:** The backend must know when a device is gone, including silent drops.
- **Decision:** Server-side auto-ping on the WebSocket plus a `lastSeen` timestamp; device is
  offline on close or ping timeout.
  - `quarkus.websockets-next.server.auto-ping-interval=30s`. Any frame or pong refreshes the
    device's in-memory `lastSeen` (`DeviceState.touch()`).
  - WebSockets Next has no "pong timeout" setting, so `LivenessCheck` (scheduled every 10 s)
    closes connections silent for more than `devices.liveness-timeout=90s` (3 missed pings).
    Closing triggers `@OnClose`, which marks the device offline — one code path for both cases.
- **Rejected:**
  - MQTT Last Will — not available without a broker.
  - Relying on TCP alone: a half-open connection (power loss, NAT drop) is only noticed when a
    write fails, which can take many minutes.
- **Consequences:** A vanished device is offline after at most ~110 s (90 s timeout + 10 s check
  interval + Vert.x's 10 s close-handshake timeout, because a dead peer never answers the close
  frame). Verified by hand with a client that never answers pings. Pings cost one tiny frame per
  device every 30 s — negligible next to telemetry.

## ADR-004 — Command delivery: own ack protocol

- **Status:** Accepted
- **Context:** Operators need feedback whether a command arrived and was executed.
- **Decision:** Each command carries a `commandId`; the device replies with `ack`.
  Status flow: `PENDING → SENT → ACKED / FAILED / TIMEOUT`.
- **Rejected:** Fire-and-forget — no feedback to the operator.
- **Consequences:**
  - `POST /api/devices/{id}/commands` stores a `Command` row (PENDING); device offline →
    FAILED "device offline" at once; online → SENT and the frame
    `{"type":"command","commandId",...}` goes out. It answers **202 Accepted** with the status
    right now — the outcome comes later with the ack. `GET` on the same path = history, newest first.
  - `ack` OK → ACKED, anything else → FAILED, the device's message is stored. A scheduled job
    (`commands.timeout=10s`, checked every 2 s) turns SENT commands without an ack into TIMEOUT,
    so a TIMEOUT shows up 10–12 s after sending.
  - **At-most-once:** no retries. A command lost on the way (or whose ack is lost) ends as
    TIMEOUT and the operator decides whether to resend. Retrying automatically would need
    idempotent commands on the device (dedupe by `commandId`) — not worth it for a demo.
  - Acks are matched by `commandId` + device, not by socket, so an ack sent after a reconnect
    still counts. A device that loses the command (e.g. power loss before acking) → TIMEOUT.
  - Metrics: `devices_commands_sent_total` (frames written), `devices_commands_acked_total`
    (status OK).

## ADR-005 — Persistence: Postgres for registry + commands, telemetry in memory

- **Status:** Accepted
- **Context:** Telemetry volume grows with device count × frequency; registry and command
  log are small and need durability.
- **Decision:** PostgreSQL + Hibernate ORM Panache for the device registry and the command
  log. Telemetry stays in memory (ring buffer, last N points per device).
- **Rejected:** Writing every telemetry message to Postgres — would be the first bottleneck.
  Production answer: a time-series DB (TimescaleDB / InfluxDB) — documented, not built.
- **Consequences:**
  - The database is written only on `hello` and on disconnect; telemetry is a synchronized
    `ArrayDeque` per device (`DeviceState`, last 100 points). The REST API merges both: registry
    fields from Postgres, latest values / history / live `lastSeen` from memory.
  - History is lost on restart and only exists on the instance holding the socket.
  - Memory grows with devices × 100 points (each point is a small map). To be measured in
    Step 6; the knob is `DeviceState.HISTORY_SIZE`.

## ADR-006 — Portal live updates: SSE, batched server-side

- **Status:** Accepted
- **Context:** The portal needs live device state; updates flow server → browser only.
- **Decision:** Server-Sent Events from Quarkus, batched (≤ 1 push/s containing only devices
  that changed).
- **Rejected:**
  - WebSocket to the browser — not needed for one-way updates.
  - Pushing every telemetry message — would flood the browser.
- **Consequences:**
  - `GET /api/stream` (`text/event-stream`), one JSON batch per second:
    `{ts, stats, devices: [DeviceSummary…], commands: [Command…]}`. The portal loads the initial
    state via REST and applies batches on top (same `DeviceSummary` shape as the REST list).
  - **Dirty sets:** the socket and the command service only *mark* ids (`LiveUpdates.deviceChanged`
    / `commandChanged`, a concurrent set — cheap, any thread). A 1 s `@Scheduled` tick drains the
    sets, loads the current values (devices from Postgres in chunks of 1000 + memory, commands from
    Postgres) and emits one batch. Cost per second ∝ devices that changed, not messages received:
    a device sending 10 frames/s appears once. Pongs don't mark a device (would make every device
    dirty every 30 s for nothing visible).
  - The batch is sent even when empty: it carries the stats and acts as a heartbeat (keeps
    proxies from closing an idle stream).
  - Fan-out via a Mutiny `BroadcastProcessor` (hot stream). A browser that can't take a batch
    per second skips batches (`onOverflow().drop()`) instead of the server buffering for it;
    devices keep changing, so it catches up with the next batch. A skipped *command* change stays
    stale until the user reopens the device (history is re-fetched via REST).
  - Every browser gets every changed device, even ones not on its current page. At 10k devices
    sending every 5 s that's ~2k summaries/s ≈ a few hundred KB/s per browser — fine for a
    handful of operators; measured in Step 6. Next step if needed: per-subscriber filter
    (stream only the page/device the user is looking at).

## ADR-007 — Scale-out: single instance, measured

- **Status:** Accepted (single instance) / Proposed (multi-instance design)
- **Context:** "Must scale to many devices"; limited time (1–2 days).
- **Decision:** Build and measure a single instance. Document the multi-instance design:
  sticky load balancer + connection registry + Redis pub/sub to route a command to the node
  that holds the device's socket.
- **Rejected:** Building the cluster now — not enough time; one honest measurement beats an
  unproven cluster.
- **Consequences:**
  - Measured (`docs/scaling.md`): one instance held 15k devices (3k msgs/s) at ~0.3 core and
    10k devices at 10k msgs/s at ~0.35 core; ~11 KB heap per connection. The first limit hit
    was the test machine's ephemeral ports (~16k), not the backend.
  - The weak spot found: blocking DB work (`hello` upsert) shares the worker pool with
    telemetry, so a DB outage stalls all device traffic. Fixes listed in `docs/scaling.md`.
  - Multi-instance stays a design: the registry, command routing and the SSE dirty sets are all
    per instance today.

## ADR-008 — Simulator: own Java app

- **Status:** Accepted
- **Context:** Load and demo need many devices that behave like devices (acks, interval
  changes, reboots).
- **Decision:** Own plain-Java app using the JDK `java.net.http.WebSocket`, N virtual devices
  in one JVM on a scheduled executor.
- **Rejected:** k6 / artillery — can't easily emulate device behaviour (acks, interval changes).
- **Consequences:**
  - One `ScheduledExecutorService` (one thread per CPU core) and one `HttpClient` for all
    devices; a device is just an object with a timer, so thousands fit in one JVM.
  - The JDK WebSocket allows only one send in flight; telemetry (timer thread) and acks (listener
    thread) can overlap, so each device chains its sends (`lastSend.thenCompose(...)`).
  - Load-shaping that matters at scale: `--ramp` (devices started per second) avoids a connect
    storm; each device's first telemetry is at a random offset inside the interval, so 10k
    devices don't all send in the same millisecond; reconnects use exponential backoff
    (1 → 30 s) with jitter. Verified: after a backend restart, 100 devices reconnected spread
    over ~30 s instead of all at once.
  - Device-type logic sits behind `DeviceModel` (`Thermostat`, `Relay`); a new type is one class
    plus one line in `DeviceModel.create`. Common commands are in `VirtualDevice.handleCommand`.
  - One extra dependency: Jackson (JSON), packaged with `maven-shade-plugin` into a fat jar.
  - Limit to expect in Step 6: one source IP has ~16k ephemeral ports on macOS towards one
    backend port → beyond that, several simulator processes / loopback aliases.

## ADR-009 — Out of scope (deliberately)

- **Status:** Accepted
- **Decision:** Not built: device auth/TLS (production: per-device token in the handshake
  header, mTLS), multi-instance, Kubernetes, OTA firmware updates, multi-tenancy, telemetry
  persistence, Grafana dashboards.
- **Consequences:** Listed in the README so the scope is explicit.

## ADR-010 — Database in dev: Quarkus Dev Services; docker-compose for prod-like runs

- **Status:** Accepted
- **Context:** Every developer (and the interview laptop) needs a Postgres with zero setup.
- **Decision:** `./mvnw quarkus:dev` and tests use Dev Services (Quarkus starts a throwaway
  Postgres container). `docker-compose.yml` provides Postgres for the prod profile.
  Schema is generated by Hibernate (`drop-and-create` in dev, `update` in prod).
- **Rejected:** Flyway migrations — the right thing for production, overhead for a demo with
  two tables.
- **Consequences:** Docker is required to run the backend in dev mode.

## ADR-011 — Threading: device callbacks on worker threads, never on the event loop

- **Status:** Accepted
- **Context:** Quarkus/Vert.x serves every socket from a few event-loop threads (≈ 2 × CPU
  cores). Blocking one of them (e.g. a JDBC call) stalls *every* connection it serves. Our
  WebSocket callbacks do blocking work: `hello` upserts the `Device` row, close marks it offline
  (Hibernate ORM + JDBC are blocking APIs).
- **Decision:** All `DeviceSocket` callbacks return `void`. WebSockets Next treats such methods
  as blocking and runs them on the **worker thread pool**, so the event loop only does network
  I/O. Database writes live in `DeviceService` (`@Transactional`). Verified in the log: callbacks
  run on `vert.x-worker-thread-N`.
- **Rejected:**
  - `@RunOnVirtualThread`: cheaper threads, good for connect storms — but the real limit during
    a storm is the DB connection pool (default 20), not threads. Worker threads are the Quarkus
    default and need no explanation of pinning rules. Easy switch later (one annotation).
  - Reactive (Hibernate Reactive + `Uni` return types): non-blocking end to end, but a second
    programming model to learn and explain for a gain we haven't measured we need.
- **Consequences:** Every telemetry frame also hops to a worker thread (cheap, but a context
  switch). If Step 6 shows the worker pool as a bottleneck, telemetry handling (memory-only) can
  be made `@NonBlocking` while `hello`/close stay on workers.

## ADR-012 — Connection registry: one socket per device, newest wins

- **Status:** Accepted
- **Context:** Commands (Step 3) must find "the" socket of a device. A device that reboots may
  reconnect before the server has noticed that its old socket is dead.
- **Decision:** `ConnectionRegistry` is a `ConcurrentHashMap<deviceId, WebSocketConnection>`.
  A new connection replaces the old one and the old one is closed. On close, the entry is
  removed only if it still points to the closing socket (`remove(key, value)`), and only then is
  the device marked offline — so the old socket's close cannot mark a reconnected device offline.
  The `online` column in Postgres mirrors the registry so the device list can filter and page in
  SQL. On startup all devices are reset to offline (no sockets exist yet).
- **Rejected:**
  - Rejecting the second connection: a rebooted device would be locked out until the old
    socket times out (up to ~110 s, see ADR-003).
  - Deriving `online` only from the registry: always correct, but filtering/paging thousands of
    devices would then happen in Java instead of SQL.
- **Consequences:** The registry only knows this instance's sockets (multi-instance: ADR-007).
  A tiny race remains: if a close and a reconnect of the same device hit the DB at the same
  moment, the row can show offline until the next `hello`. Acceptable for a demo; the fix would
  be a connection generation number on the row.

## ADR-013 — Command validation: the device decides, the backend only checks the shape

- **Status:** Accepted
- **Context:** Commands have names and params (`setInterval {seconds}`, `setTargetTemp {target}`,
  `setRelay {on}`, `reboot {}`, wire-protocol table in `PLAN.md`). Someone has to reject
  `setTargetTemp {"target": 99}` or a command a relay doesn't support.
- **Decision:** The backend checks only what it needs to route and store a command: the device
  exists (else 404), the body has a non-blank `name` (else 400), `params` is a JSON object
  (default `{}`). Name and params are forwarded unchanged. The **device** validates and acks
  `ERROR` with a message ("target must be a number between 5 and 30", "unknown command X"),
  which ends up as FAILED + message in the command log.
- **Rejected:**
  - Validating every command per device type in the backend as well: the rules would live in
    two places (backend + firmware) and drift apart; every new command or firmware version would
    need a backend release. The device is the only one that knows what its firmware supports.
- **Consequences:**
  - Adding a command on the device side needs no backend change — the portal can send it at once.
  - An invalid command costs one round trip to the device instead of an instant 400. The
    operator still sees why it failed (the ack message).
  - A device with buggy validation could accept nonsense; acceptable — it would also be the
    device executing it. The portal (Step 5) offers only the known commands with sensible inputs.

## ADR-014 — Command status changes: short transactions, conditional updates

- **Status:** Accepted
- **Context:** Three parties change a command's status concurrently: the REST request (send), the
  device socket (ack) and the timeout job. On localhost the simulator acks in well under a
  millisecond — faster than a transaction commits.
- **Decision:** `CommandService` is the one place for the flow. Each status change is its own
  short transaction (`QuarkusTransaction.requiringNew()`), never held open while writing to the
  network. SENT is **committed before** the frame is written, so an ack can never arrive for a
  command still shown as PENDING. Ack and timeout are conditional updates
  (`... where id = ? and deviceId = ? and status = 'SENT'`): whichever comes first wins, the
  other updates 0 rows and is ignored (logged). The `deviceId` condition stops a device from
  acking another device's command.
- **Rejected:**
  - One `@Transactional` around persist + send + mark SENT: holds a DB connection during network
    I/O, and the ack's transaction can't see the uncommitted command → lost ack, false TIMEOUT.
  - Mark SENT only after the send succeeded: the ack can overtake it and be overwritten with
    SENT (we'd then report TIMEOUT for an executed command).
  - Optimistic locking (`@Version`) with retries: same safety, more code to explain.
- **Consequences:**
  - If writing the frame fails, the command goes SENT → FAILED "send failed: ...".
  - A crash between PENDING and SENT leaves a PENDING row forever (nothing was sent, so it's
    harmless, but it looks odd in the history). The timeout job could also expire old PENDING
    rows — not done.
  - Three small DB writes per command (insert, SENT, result). Commands are rare compared to
    telemetry, so fine; the timeout check is one `UPDATE` using an index on `status`.
  - `Command.params` is a Postgres `jsonb` column (`@JdbcTypeCode(SqlTypes.JSON)`), so the REST
    API returns params as an object. Needed `quarkus.hibernate-orm.mapping.format.global=ignore`
    (Hibernate's own JSON mapper, not the REST one — we don't customize Jackson).

## ADR-015 — Portal: plain Angular, server-side paging, REST snapshot + SSE deltas

- **Status:** Accepted
- **Context:** The portal must show thousands of devices live and send commands. The author
  must be able to explain every part; the interview may extend it live.
- **Decision:**
  - Plain Angular (standalone components, signals, zoneless default) and hand-written CSS — no UI
    framework. Components: `DeviceList`, `DeviceDetail`, `CommandPanel`, `Sparkline`; services
    `DeviceApi` (all REST calls) and `LiveStream` (the one `EventSource`).
  - **Server-side paging + filters** (`?page&size&online&type&q`) instead of loading all devices:
    the browser holds 50 rows, however many devices exist.
  - **Snapshot + deltas:** a page loads its data via REST, then merges each SSE batch by id
    (list: rows of the current page; detail: device, history, commands). A new device registered
    (stats `totalDevices` changes) triggers a reload of the list page.
  - **`device-types.ts` is the one place** for device types and commands on the portal side:
    per type the metrics (sparklines), commands with param inputs, and the list's key value.
    Unknown types still get the common commands; a "custom" command row sends any name/params.
  - Sparkline = ~30 lines of SVG `polyline`, no chart library.
  - "x s ago" uses the server time of the last batch as its clock — no extra timer.
- **Rejected:**
  - Angular Material / a chart library: more to explain and to download for a table, some
    inputs and a line; nothing here needs them.
  - CDK virtual scroll over all devices: would need every device in the browser (10k+ rows of
    live data) — paging keeps both REST and the browser small.
  - NgRx/global store: two pages with local state; a signal per thing is enough.
- **Consequences:**
  - The detail chart gets at most one new point per second from the stream (the latest);
    a device sending faster shows fewer points live than the backend's history has.
  - A list filtered to "online" keeps showing a device that just went offline (badge turns
    grey) until the next reload — the rows update in place, they aren't re-filtered.
  - Min/max on the inputs are hints only; out-of-range values reach the device and come back as
    FAILED with its message (ADR-013) — useful in the demo.
