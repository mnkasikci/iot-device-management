# CLAUDE.md — rules for AI sessions in this repo

IoT device management demo: devices hold a persistent WebSocket to a Quarkus backend,
an Angular portal shows and controls them. `PLAN.md` is the source of truth for scope,
decisions and steps; each step runs in its own session ("Read PLAN.md and do Step N").

## Rules

1. **Small, verified steps.** Change one thing, build it, check it works, then move on.
   Never stack several unverified changes.
2. **Run what you build.** "It compiles" is not done. Start the app / run the test / hit the
   endpoint and look at the output before saying a step is finished.
3. **No unexplained dependencies.** Every new library, extension or npm package needs a
   one-line reason in the commit message or `docs/decisions.md`. Prefer what Quarkus / the
   JDK / Angular already ship.
4. **Every design choice goes into `docs/decisions.md`** as a short ADR (context, decision,
   rejected alternatives, consequences). If it isn't written down, it can't be defended.
5. **Keep it simple and explainable.** The author must be able to defend every line in an
   interview. Boring and obvious beats clever.
6. **One obvious place for protocol logic.** Command names and device-type handling live in
   one place on each side (backend, simulator) — that's where new features will land.
7. **Commits:** small, conventional-commit messages in English (`feat:`, `fix:`, `docs:`,
   `chore:`, `test:`). Propose commits to the user; don't push without asking.

## Layout & commands

| Path | What | Run |
|---|---|---|
| `backend/` | Quarkus (Java 21 target), WebSockets Next, REST, Panache, Postgres | `cd backend && ./mvnw quarkus:dev` (Dev Services starts Postgres; needs Docker) |
| `simulator/` | plain Java, N virtual devices via `java.net.http.WebSocket` | `cd simulator && ./mvnw -q package && java -jar target/simulator.jar` |
| `portal/` | Angular (standalone components, signals, no SSR) | `cd portal && npm start` (proxies `/api` → `localhost:8080`) |
| `docker-compose.yml` | Postgres for prod-like runs | `docker compose up -d` |
| `docs/` | `decisions.md` (ADRs), later `scaling.md` | — |

Ports: backend 8080, portal 4200, compose Postgres 5433. No global Maven — always use `./mvnw`.
Local JDK is 27; code targets Java 21 (`maven.compiler.release=21`).

**Colima (this machine):** Docker runs in Colima, whose socket is not at `/var/run/docker.sock`,
so Testcontainers/Dev Services can't find it by default. Export before `quarkus:dev` / tests:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```
