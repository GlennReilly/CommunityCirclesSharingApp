# Circles

A local-first sharing network for small groups of neighbours: lend tools, offer skills, and help each other without money, credit scores or a central company. Working title; see [the design doc](docs/spec/community-sharing-app-design.md) for the why and [the spec](docs/spec/community-sharing-app-spec.md) for what to build.

## Status

**M0 (skeleton)** and **M1 (event log and sync)** are done, plus rate limiting, JSON logging and a single-jar build pulled forward from M5. M1 gives you signed events in a deterministic CBOR encoding, a SQLite-backed server with push, pull and challenge-response login, the `serve` and `migrate` commands, and an acceptance test in which two JVM test clients sync a circle (in-process and over a real socket). Payloads are plaintext in M1 (envelope `v = 0`, [ADR 0014](docs/adr/0014-m1-server-rules-and-dev-payload-format.md)); **M2** adds encryption. The API is in [docs/api.md](docs/api.md).

## Layout

| Folder | What it holds |
|---|---|
| `shared/` | Kotlin Multiplatform (JVM + JS): policy (tiers, standing, access), CBOR encoding, envelope, validation, API types, `Crypto` interface (JVM implementation) |
| `server/` | Ktor server: `domain/` (rules, services), `data/` (SQLite, migrations), `web/` (routes), `config/`, CLI |
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

Run the server (a brand-new database is created and migrated on first start):

```bash
./gradlew :server:run --args=serve
./gradlew :server:run --args=migrate     # apply migrations without starting
```

Settings (environment variables, all optional):

| Variable | Default | Meaning |
|---|---|---|
| `CIRCLES_DATA_DIR` | `./data` | Where `circles.db` lives |
| `CIRCLES_BIND`, `CIRCLES_PORT` | `0.0.0.0`, `8080` | Listen address |
| `CIRCLES_ALLOW_CREATE` | `true` | `false` stops strangers creating circles |
| `CIRCLES_RATE_LIMIT` | `true` | Per-address and per-device request limits ([ADR 0015](docs/adr/0015-rate-limiting-pulled-forward.md)) |
| `CIRCLES_TRUST_PROXY` | `false` | Behind a reverse proxy, take the client address from the last `X-Forwarded-For` entry; otherwise every client looks like the proxy and shares one limit |
| `CIRCLES_LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error` |

Logs are one JSON object per line on stdout. They never contain bodies, headers, tokens, keys, client addresses or exception messages ([ADR 0016](docs/adr/0016-structured-json-logging.md)).

Build a single runnable jar (about 39 MB, [ADR 0017](docs/adr/0017-shadow-jar-for-single-file-deploy.md)) and run it anywhere with Java 17+:

```bash
./gradlew :server:shadowJar
java -jar server/build/libs/circles-server.jar serve
```

## Principles in one paragraph

A sharing network, not a currency. Trust is local to each circle, access widens with contribution and fades gently, and nobody is banned. Everything is an append-only signed event log, so any group can move to a new host from a backup or from any member's device. The server stores ciphertext and cannot read item, loan or vouch details.
