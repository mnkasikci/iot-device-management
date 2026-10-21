# Scaling — what one instance does, measured

**Short answer:** one backend instance held **15,000 connected devices** (3,000 msgs/s) using
~0.3 of one CPU core, and **10,000 devices at 10,000 msgs/s** using ~0.35 of a core, with
commands acked in ~10–20 ms. What broke first was **not the backend** but the test setup: a
single Mac can't open more than ~16k outgoing connections (ephemeral ports). Pushing past that
showed a real weakness: when the database becomes unreachable, blocking `hello` upserts take every
worker thread, and with them all device and REST processing.

All numbers below are from 2026-10-21, on one laptop. They are an honest order of magnitude, not
a benchmark.

## Setup

| | |
|---|---|
| Machine | Apple Silicon, 10 cores, 16 GB RAM, macOS (Darwin 25.5) |
| Backend | `java -jar target/quarkus-app/quarkus-run.jar`, prod profile, JDK 27, default JVM flags (G1, max heap = ¼ RAM) |
| Database | Postgres 18 in Docker (Colima VM), reached via port forward `localhost:5433` |
| Devices | `simulator.jar` (one shared `HttpClient`, scheduler with 10 threads), several processes on the **same machine** |
| Mix | 50 % thermostat / 50 % relay; telemetry every 5 s (or 1 s for the throughput run) |

## Method

1. Start the backend and Postgres (above), then add simulator processes level by level
   (`--count 1000|4000|5000 --interval 5 --ramp 500 --prefix a|b|c…`, so device ids don't clash).
2. Wait until all are connected, then run `python3 loadtest/measure.py --pid <backend pid>`
   (Python standard library only). It prints one table row:
   - **connected, msgs/s:** `GET /api/stats`
   - **CPU %, RSS:** `ps`, CPU averaged over 5 s, 100 % = one core
   - **heap, threads:** `/q/metrics` (heap is "used right now", including garbage)
   - **list ms:** one `GET /api/devices?size=50`
   - **command round trip:** `setInterval` (same value, so the load doesn't change) to 20 online
     devices, then wait for ACKED; time = `completedAt − createdAt` from the command log
     (server side: insert → SENT → device → ack → ACKED)
   - **SSE:** read `/api/stream` for 5 s: average batch size and devices per batch
3. Portal: headless Chrome (Playwright) on the device list for 10 s, counting long tasks
   (> 50 ms on the main thread), then click a device and send a command.

## Results

### Connections (telemetry every 5 s)

| devices | msgs/s | CPU % | RSS MB | heap MB | threads | list ms | cmd acked | cmd p50 ms | cmd p95 ms | SSE KB/batch | devices/batch |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1,000 | 204 | 21 | 306 | 62 | 137 | 77¹ | 20/20 | 17 | 29 | 45 | 198 |
| 5,000 | 990 | 12 | 484 | 78 | 114 | 12 | 20/20 | 13 | 15 | 229 | 1,001 |
| 10,000 | 1,936 | 14 | 441 | 199 | 142 | 16 | 20/20 | 13 | 18 | 454 | 1,990 |
| 15,000 | 3,000 | 28 | 307 | 476 | 227 | 28 | 20/20 | 12 | 19 | 691 | 3,029 |
| ~16,200 | — | — | — | — | — | — | — | — | — | — | — |

¹ first request after start (cold JIT). At ~16,200 the simulators couldn't open more connections:
see "What broke first" below.

### Throughput (10,000 devices, telemetry every 1 s)

| devices | msgs/s | CPU % | RSS MB | heap MB | threads | list ms | cmd acked | cmd p50 ms | cmd p95 ms | SSE KB/batch | devices/batch |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10,000 | 9,993 | 34 | 1,172 | 563 | 106 | 37 | 20/20 | 10 | 19 | 2,278 | 9,983 |

### Memory per connection

With 10,000 connected, after a forced GC (`jcmd <pid> GC.run`), the heap was 438 MB; after all
10,000 disconnected (their in-memory `DeviceState` stays) it was 332 MB. That's
**≈ 11 KB of heap per open WebSocket connection**: Vert.x/Netty connection objects, the registry
entry and the WebSocket Next connection. Native socket buffers (kernel, Netty direct memory) are
not included. The `DeviceState` with its 100-point history is extra and stays after a disconnect
(20,000 known devices, half with full history, accounted for most of the 332 MB).

### Storms

| What | Result |
|---|---|
| Connect storm: 10,000 devices within ~2 s (2 × `--ramp 2500`) | all 10,000 `online` in the DB after **~5 s** (≈ 2,000 `hello` upserts/s), no DB pool errors |
| Disconnect storm: kill 10,000 devices at once | DB shows 0 online after **≤ 3.1 s** (≈ 3,300 offline writes/s), no errors |

### Portal (10,000 devices at 1 s, every device in every SSE batch)

| What | Result |
|---|---|
| Main-thread long tasks (> 50 ms) in 10 s on the device list | **0** (parsing a ~2.3 MB batch per second stays under 50 ms) |
| JS heap | 19–28 MB |
| Click on a device → detail page rendered | 115–134 ms |
| Click "send" → ACKED visible in the history | 205–468 ms (bounded by the 1 s SSE batch) |

## What broke first, and why

### 1. Client-side ephemeral ports (~16k connections per machine)

macOS gives outgoing connections a local port from 49152–65535 = **16,384 ports**. At ~16,200
concurrent simulator connections every new outgoing connection failed. The rest were in use by
other connections and `TIME_WAIT`. This included `curl` to the backend's **LAN IP**: on macOS the
pool is shared by the whole machine, not one pool per destination, so the "second destination
address" trick from the plan didn't help. The backend at that moment: 28 % of one core, ~0.5 GB heap.

→ This limits the **test harness**, not the server. Each real device has its own IP. To go
further: run simulators on other machines or in containers/VMs with their own network stack,
or widen the range (`sudo sysctl net.inet.ip.portrange.first=10000`; needs root, not done).

### 2. Cascade when the DB connection breaks (found by accident)

The run that collapsed: 3 simulators × 5,000 devices at a 1 s interval, ramp 3 × 500/s, started
seconds after 15,000 devices had disconnected (thousands of ports still in `TIME_WAIT`). Ports ran
out again, and then:

- The DB pool couldn't open replacement connections (it reaches Postgres via `localhost:5433`,
  which also needs an ephemeral port), giving ~1,000 `Sorry, acquisition timeout!` errors.
- A thread dump showed **all 200 worker threads in `DeviceService.onHello` → `Device.findById`**:
  190 waiting for a pooled connection, 9 talking to Postgres, 1 opening a socket.
- Because WebSocket callbacks and REST share that worker pool, **telemetry processing and the
  REST API stalled too** (`/api/stats` timed out after 30 s). Devices timed out and reconnected,
  which made more `hello`s and more churn.
- Colima's SSH port forward (it carries Postgres *and* the Docker socket) died during this and only
  came back with `colima restart`. That's a quirk of the local setup.

With fewer devices, the same workload doesn't fail: the clean connect storm above went through
without errors. But the cascade shows the real design weakness: **one slow dependency (the DB)
can stop all device traffic**, because blocking DB work and memory-only telemetry share one
thread pool (ADR-011).

Fixes, in order of effort (none built yet):
1. Make telemetry `@NonBlocking` (memory only, so it can run on the event loop). Telemetry then keeps
   flowing even when every worker waits for the DB. ADR-011 already names this as the next step.
2. Take the `hello` upsert off the connection path: put it in a queue and batch-upsert it
   (e.g. every 100 ms, one statement for many devices). A connect storm then costs a few
   statements instead of one transaction per device.
3. Admission control: limit WebSocket handshakes per second, and tell devices to back off (they
   already reconnect with jittered backoff, which is what made the recovery possible).

### 3. Next limits (expected, not reached)

- **SSE size per browser:** grows with *changed devices per second*: 2.3 MB/s at 10k devices
  sending every second. Fine for a few operators on a LAN, too much for many browsers or slow
  links. Fix: stream only what a browser is looking at (current page or device), and send the
  totals as numbers (ADR-006).
- **DB writes on connect/disconnect:** ~2,000 upserts/s and ~3,300 offline writes/s with the
  default pool of 20. Batching (above) raises that by an order of magnitude.
- **One machine:** connections are cheap (~11 KB heap each, low CPU), so file descriptors
  (`kern.maxfilesperproc` = 61,440 here) and kernel socket memory would come before CPU. For
  hundreds of thousands of devices: several instances (ADR-007), or an MQTT broker built for
  millions of connections (ADR-002).

## Going beyond one instance

See ADR-007. Devices connect through a load balancer to any of N instances. Each instance keeps
its own `ConnectionRegistry` and publishes "device X is on node N" (Redis). A command for device X
goes to the node that holds its socket (Redis pub/sub or a direct call). SSE batches are merged per
node or come from a shared pub/sub channel. Telemetry history moves out of the JVM into a
time-series database (TimescaleDB/InfluxDB) once it has to survive restarts or be shared between nodes.

## Reproduce

```bash
docker run -d --rm --name iot-pg -e POSTGRES_DB=devices -e POSTGRES_USER=devices \
  -e POSTGRES_PASSWORD=devices -p 5433:5432 postgres:18
cd backend && ./mvnw package -DskipTests && java -jar target/quarkus-app/quarkus-run.jar &
cd simulator && java -jar target/simulator.jar --count 5000 --interval 5 --ramp 500 --prefix a &
python3 loadtest/measure.py --pid <backend pid>
```
