# Lendger

A local-first sharing network for community groups: lend tools, offer skills, and help each other without money, credit scores or a central company. See [the design doc](docs/spec/community-sharing-app-design.md) for the why and [the spec](docs/spec/community-sharing-app-spec.md) for what to build.

## What Lendger is for

Community groups, clubs and friends already lend drills, ladders and prams, and swap skills like bike repair or tax help. Today that usually runs on group chats and spreadsheets, or on platforms that want money, personal data or an account. Nobody can see who has what. It is hard to know whom to trust. And a central company holds everyone's details.

Lendger is a web app for one group (a "circle") to do this for itself, with no money, no ratings, no ads and no company in the middle.

### Lend and borrow

- Members list things they own, with a private description and up to three photos. Only members of the circle can see them.
- Someone asks to borrow an item for some dates. The owner approves (or suggests other dates), hands it over, and confirms the return. An item cannot be promised twice for the same days.
- Reminders for returns show inside the app. Nothing is sent by email.
- Reporting damage or loss is a normal, no-blame step. A steward (see below) can settle a disagreement.
- QR codes can tag an item, or let two people check each other in person.

### Offer skills

- A skills directory lists what members can help with. It is a noticeboard: people then talk outside the app, and there is no messaging and no money.

### Trust that grows, with no bans

- New members can borrow lightly. Members are at Level 1, 2 or 3: Level 1 for new people, higher levels as they take part ([ADR 0070](docs/adr/0070-level-numbers.md)). As they return things well and help out, they earn "standing", which slowly fades if they stop taking part ([ADR 0042](docs/adr/0042-standing-vouches-and-the-access-check.md)).
- Members choose the name others see. A member at Level 2 or above (or a steward) who has met someone and checked them, by scanning their QR code or, with no camera, reading a few characters out to each other, can confirm their identity; every page then shows "(identity confirmed)", and changing the name clears it ([ADR 0068](docs/adr/0068-verified-display-names.md), [ADR 0071](docs/adr/0071-checking-identity-in-person.md)). Stewards let new people in the same way.
- Members can vouch for a new member. Contribution notes (for example, helping at a working bee) also count.
- There are no star ratings. Owners decide who borrows their things; standing is advice shown to them.
- As a last resort, members at Level 3 can pause someone. A pause needs several reviewers, has an end date and an appeal, and keeps essentials open ([ADR 0050](docs/adr/0050-pauses.md)). Nobody is banned for good.

### Your data stays private

- Everything private (item details, photos, loans, vouches, notes) is locked on your device before it leaves it. The server only stores locked data and holds no key, so even the person running it cannot read it ([ADR 0021](docs/adr/0021-sealed-envelope-v1-and-circle-key-wrapping.md)).
- The host can still see that a circle exists, who is a member, and when members use it. This is stated plainly in the design doc.
- When a member is removed, the circle's key is changed so they cannot read what comes next.

### No accounts, no email

- You do not sign up. Your keys are made in your browser and stay there, locked by a passphrase. You can also unlock with your device's passkey or fingerprint where the browser supports it.
- A 12-word recovery code lets you get back in if you lose a device. Stewards can also hold pieces of the circle's key, so the group can recover it together.
- You join through an invite link. You can add a second device (your phone and your laptop) after comparing a few characters on both screens.

### Works offline, installs like an app

- It is a web page you can install on a phone or computer. It keeps your circle on the device, so it works without a connection and syncs when you are back online.
- Text size and light or dark colours can be changed. The text is written to be easy to read, and automated accessibility checks pass.

### Run by the community

- A circle has stewards. Important changes (adding or removing a steward, changing the circle's rules) need several of them to agree. If stewards go quiet, a fallback lets the rest carry on ([ADR 0027](docs/adr/0027-steward-quorum-setting-and-inactivity-fallback.md)).
- Anyone can run the server themselves, on a small computer or a cheap rented one. Operator guides are in [docs/operator/](docs/operator/README.md).
- A circle is not locked to its host. A steward can export it, or any member's copy can be used, and the group can move to a new host and carry on ([ADR 0030](docs/adr/0030-circle-export-import-and-verify.md)). The server also has backup and restore.

### Where it stands

The features above are built and tested: unit tests on both the server and browser sides, tests against a real server, and a script that drives real Chrome. The server was load-tested with 300 members in one circle on a 2012 desktop ([requirements](docs/operator/requirements.md)); nothing larger has been measured.

**Not done yet** (see [docs/pilot-readiness.md](docs/pilot-readiness.md) and [docs/remaining-steps.md](docs/remaining-steps.md)):

- A real install over HTTPS on a Raspberry Pi or a rented server. The guides were checked only in containers.
- A trial on real phones, including passkeys (tried only with Chrome's test authenticator) and how long unlock takes.
- A screen-reader pass of the borrowing steps.
- Someone who has not seen the code following the operator guides.
- A pilot with a real group. The default trust thresholds are to be tuned after it.

The API is in [docs/api.md](docs/api.md); the technical decisions are in [docs/adr/](docs/adr/README.md).

## Layout

| Folder | What it holds |
|---|---|
| `shared/` | Kotlin Multiplatform (JVM + JS): policy (tiers, standing, access), CBOR encoding, envelope, validation, the event fold, steward rules, items and loans, export format, `Crypto` and `Cipher` (JDK/libsodium on the JVM, noble in the browser), the client sync engine and app logic |
| `server/` | Ktor server: `domain/` (rules, services), `data/` (SQLite, migrations, blob files), `backup/` (export and import), `web/` (routes), `config/`, CLI |
| `web/` | The browser client: Kotlin/JS with kotlinx-html, no TypeScript ([ADR 0009](docs/adr/0009-web-ui-stack.md)); views, state, service worker, styles. See [web/README.md](web/README.md) |
| `deploy/` | Dockerfile, compose file with an optional HTTPS proxy, Caddyfile, systemd unit |
| `tools/` | Independent reference generators for test vectors, the real-Chrome end-to-end scripts (`browser-e2e`) and bundle analysis |
| `docs/operator/` | Guides for the person running a host |
| `docs/` | Also [api.md](docs/api.md), [pilot-readiness.md](docs/pilot-readiness.md) and [remaining-steps.md](docs/remaining-steps.md) |
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

Run the web client's own tests, and check that its script stays under the size budget (300 KiB gzipped, part of `check`):

```bash
./gradlew :web:jsNodeTest
./gradlew :web:checkBundleBudget
```

Run the browser end-to-end check (needs Chrome, Node 22+ and the jar; details in [tools/browser-e2e/README.md](tools/browser-e2e/README.md)):

```bash
./gradlew :server:shadowJar
(cd tools/browser-e2e && npm ci)
A11Y=1 node tools/browser-e2e/e2e.mjs
```

Run the server, which also serves the web client at `/` (a brand-new database is created and migrated on first start):

```bash
./gradlew :server:run --args=serve
./gradlew :server:run --args=migrate     # apply migrations without starting
```

Move a circle to another host (needs no key and no server secret, [ADR 0030](docs/adr/0030-circle-export-import-and-verify.md)):

```bash
java -jar lendger-server.jar export <circleId> circle.zip   # on the old host (or build the file from any member's copy)
java -jar lendger-server.jar verify circle.zip              # check it anywhere, without a database
java -jar lendger-server.jar migrate                        # on the new host, once
java -jar lendger-server.jar import circle.zip              # re-checks every event; safe to repeat or to extend
```

After an import, existing devices log in again with the key they already have and carry on from where they stopped.

Back up and restore the whole server ([ADR 0031](docs/adr/0031-server-backup-and-restore.md)):

```bash
java -jar lendger-server.jar backup /safe/place              # database and blobs in one zip; safe while serving
java -jar lendger-server.jar verify /safe/place/lendger-backup-<time>.zip
java -jar lendger-server.jar restore lendger-backup-<time>.zip           # new host, server stopped
java -jar lendger-server.jar restore lendger-backup-<time>.zip --force   # existing data is moved aside, never deleted
```

Stewards can also export and import a circle over HTTP with a signed request and no server password (`GET /api/v1/admin/export/{circleId}`, `POST /api/v1/admin/import`, [ADR 0033](docs/adr/0033-signed-steward-admin-requests.md), details in [docs/api.md](docs/api.md)). To let a steward bring a circle onto a host that has never seen it, the host operator first runs `lendger-server import-token <circleId>` and passes on the printed single-use token ([ADR 0034](docs/adr/0034-operator-import-token-for-new-circles.md)).

`migrate` takes a full backup (database and blobs) into `LENDGER_BACKUP_DIR` first and refuses to run without that folder, so a failed or unwanted migration can be undone. Point it at another disk.

Settings (environment variables, all optional):

| Variable | Default | Meaning |
|---|---|---|
| `LENDGER_DATA_DIR` | `./data` | Where `lendger.db` lives |
| `LENDGER_BIND`, `LENDGER_PORT` | `0.0.0.0`, `8080` | Listen address |
| `LENDGER_ALLOW_CREATE` | `true` | `false` stops strangers creating circles |
| `LENDGER_RATE_LIMIT` | `true` | Per-address and per-device request limits ([ADR 0015](docs/adr/0015-rate-limiting-pulled-forward.md)) |
| `LENDGER_TRUST_PROXY` | `false` | Behind a reverse proxy, take the client address from the last `X-Forwarded-For` entry; otherwise every client looks like the proxy and shares one limit |
| `LENDGER_BLOB_QUOTA_MIB` | `256` | Blob storage allowed per circle, in MiB; uploads beyond it get `507` ([ADR 0026](docs/adr/0026-sse-hints-and-blob-storage.md)) |
| `LENDGER_BACKUP_DIR` | unset | Folder for scheduled and pre-migration backups, ideally on another disk. Required for both: scheduling and `migrate` of existing data refuse to run without it ([ADR 0032](docs/adr/0032-explicit-backup-folder-and-full-pre-migration-backup.md)) |
| `LENDGER_BACKUP_INTERVAL_HOURS` | off | Write a full backup into `LENDGER_BACKUP_DIR` every this many hours while serving ([ADR 0031](docs/adr/0031-server-backup-and-restore.md)) |
| `LENDGER_BACKUP_KEEP` | `7` | How many scheduled backups to keep |
| `LENDGER_ADMIN_IMPORT_MAX_MIB` | `64` | Largest circle a steward can import over HTTP, in MiB; use the command line for bigger ones ([ADR 0033](docs/adr/0033-signed-steward-admin-requests.md)) |
| `LENDGER_LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error` |

Logs are one JSON object per line on stdout. They never contain bodies, headers, tokens, keys, client addresses or exception messages ([ADR 0016](docs/adr/0016-structured-json-logging.md)).

Then open http://localhost:8080. Build a single runnable jar (about 41 MB, [ADR 0017](docs/adr/0017-shadow-jar-for-single-file-deploy.md)) and run it anywhere with Java 17+:

```bash
./gradlew :server:shadowJar
java -jar server/build/libs/lendger-server.jar serve
```

## Principles in one paragraph

A sharing network, not a currency. Trust is local to each circle, access widens with contribution and fades gently, and nobody is banned. Everything is an append-only signed event log, so any group can move to a new host from a backup or from any member's device. The server stores ciphertext and cannot read item, loan or vouch details.
