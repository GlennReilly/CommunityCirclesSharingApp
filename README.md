# Circles

A local-first sharing network for small groups of neighbours: lend tools, offer skills, and help each other without money, credit scores or a central company. Working title; see [the design doc](docs/spec/community-sharing-app-design.md) for the why and [the spec](docs/spec/community-sharing-app-spec.md) for what to build.

## Status

Milestone **M0 (skeleton)** is done: Gradle multi-module build, the trust-model policy in `shared` with tests (JVM and JS), a stub server with a health endpoint, ADRs, and CI. Next is **M1**: the signed event log, SQLite store and sync endpoints.

## Layout

| Folder | What it holds |
|---|---|
| `shared/` | Kotlin Multiplatform (JVM + JS): model, policy (tiers, standing, access), later encoding, validation, sync logic |
| `server/` | Ktor server (stub: `/healthz` only) |
| `web/` | Placeholder for the PWA; stack not yet decided ([ADR 0009](docs/adr/0009-web-ui-stack.md)) |
| `deploy/`, `tools/` | Placeholders for Docker, Caddy, systemd and dev scripts |
| `docs/spec/` | Design doc and implementation spec |
| `docs/adr/` | Architecture decision records |

## Build and test

Needs JDK 17+. The first run downloads Gradle, dependencies and (for the JS tests) a Node.js build.

```bash
./gradlew check
```

Run only the shared policy tests, on both targets:

```bash
./gradlew :shared:jvmTest :shared:jsNodeTest
```

Run the stub server (port 8080, override with `CIRCLES_PORT` and `CIRCLES_BIND`):

```bash
./gradlew :server:run
```

## Principles in one paragraph

A sharing network, not a currency. Trust is local to each circle, access widens with contribution and fades gently, and nobody is banned. Everything is an append-only signed event log, so any group can move to a new host from a backup or from any member's device. The server stores ciphertext and cannot read item, loan or vouch details.
