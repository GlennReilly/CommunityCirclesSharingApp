# Circles

A local-first sharing network for small groups of neighbours: lend tools, offer skills, and help each other without money, credit scores or a central company. Working title; see [the design doc](docs/spec/community-sharing-app-design.md) for the why and [the spec](docs/spec/community-sharing-app-spec.md) for what to build.

## Status

Done: **M0** (skeleton, trust policy), **M1** (signed event log and sync), **M2** (end-to-end encryption), rate limiting, JSON logging, a single-jar build, and from M3 to M5 the server and shared pieces listed below. There is **no web client yet** (M3 proper) and the access policy is not wired in (M4: standing, disputes).

- **Events and sync:** signed events in a deterministic CBOR encoding, a SQLite-backed server with push, pull and challenge-response login, optional server-sent hints on `/events/stream` ([ADR 0026](docs/adr/0026-sse-hints-and-blob-storage.md)).
- **Encryption** ([ADR 0018](docs/adr/0018-m2-libsodium-bindings.md) to [0021](docs/adr/0021-sealed-envelope-v1-and-circle-key-wrapping.md), [0024](docs/adr/0024-retire-the-plaintext-v0-format.md)): every private payload is sealed under a circle key (format `v = 1`, the only one); the server stores ciphertext and never holds a key. Circle keys are wrapped to devices, rotated, and split among stewards (Shamir).
- **Governance** ([ADR 0027](docs/adr/0027-steward-quorum-setting-and-inactivity-fallback.md), [0029](docs/adr/0029-lower-the-minimum-silent-days.md)): stewards, a per-circle quorum, and an inactivity fallback with settings the stewards control.
- **Items and loans** ([ADR 0025](docs/adr/0025-item-and-loan-event-payloads.md)) with per-item detail keys, and encrypted blobs for attachments.
- **Handover** ([ADR 0030](docs/adr/0030-circle-export-import-and-verify.md)): `export`, `import` and `verify` move a circle between hosts using only ciphertext. Server `backup` and `restore`, signed admin endpoints, Docker and load numbers are still to do.

The API is in [docs/api.md](docs/api.md); the decisions are in [docs/adr/](docs/adr/README.md).

## Layout

| Folder | What it holds |
|---|---|
| `shared/` | Kotlin Multiplatform (JVM + JS): policy (tiers, standing, access), CBOR encoding, envelope, validation, the event fold, steward rules, items and loans, export format, `Crypto` and `Cipher` (libsodium on JS) |
| `server/` | Ktor server: `domain/` (rules, services), `data/` (SQLite, migrations, blob files), `backup/` (export and import), `web/` (routes), `config/`, CLI |
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

Move a circle to another host (needs no key and no server secret, [ADR 0030](docs/adr/0030-circle-export-import-and-verify.md)):

```bash
java -jar circles-server.jar export <circleId> circle.zip   # on the old host (or build the file from any member's copy)
java -jar circles-server.jar verify circle.zip              # check it anywhere, without a database
java -jar circles-server.jar migrate                        # on the new host, once
java -jar circles-server.jar import circle.zip              # re-checks every event; safe to repeat or to extend
```

After an import, existing devices log in again with the key they already have and carry on from where they stopped.

Settings (environment variables, all optional):

| Variable | Default | Meaning |
|---|---|---|
| `CIRCLES_DATA_DIR` | `./data` | Where `circles.db` lives |
| `CIRCLES_BIND`, `CIRCLES_PORT` | `0.0.0.0`, `8080` | Listen address |
| `CIRCLES_ALLOW_CREATE` | `true` | `false` stops strangers creating circles |
| `CIRCLES_RATE_LIMIT` | `true` | Per-address and per-device request limits ([ADR 0015](docs/adr/0015-rate-limiting-pulled-forward.md)) |
| `CIRCLES_TRUST_PROXY` | `false` | Behind a reverse proxy, take the client address from the last `X-Forwarded-For` entry; otherwise every client looks like the proxy and shares one limit |
| `CIRCLES_BLOB_QUOTA_MIB` | `256` | Blob storage allowed per circle, in MiB; uploads beyond it get `507` ([ADR 0026](docs/adr/0026-sse-hints-and-blob-storage.md)) |
| `CIRCLES_LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error` |

Logs are one JSON object per line on stdout. They never contain bodies, headers, tokens, keys, client addresses or exception messages ([ADR 0016](docs/adr/0016-structured-json-logging.md)).

Build a single runnable jar (about 39 MB, [ADR 0017](docs/adr/0017-shadow-jar-for-single-file-deploy.md)) and run it anywhere with Java 17+:

```bash
./gradlew :server:shadowJar
java -jar server/build/libs/circles-server.jar serve
```

## Principles in one paragraph

A sharing network, not a currency. Trust is local to each circle, access widens with contribution and fades gently, and nobody is banned. Everything is an append-only signed event log, so any group can move to a new host from a backup or from any member's device. The server stores ciphertext and cannot read item, loan or vouch details.
