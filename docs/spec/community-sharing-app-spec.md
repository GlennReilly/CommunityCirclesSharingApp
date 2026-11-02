# Circles: implementation spec for Claude Code

Status: draft v0.2, 2026-11-02. Read `community-sharing-app-design.md` first for rationale. This document says what to build. Where it says "decide", pick the simplest option that meets the constraints and record the choice in `docs/adr/` as a short ADR (`NNNN-slug.md`, with a `README.md` index and a `template.md`).

M1 amendments (2026-11-02, ADRs 0010-0014): `UNIQUE(device, ctr)` and the counter check are dropped; `events` gains `v`; `stewards` and `invites` tables; `POST /circles` for `circle.create`; `v = 0` is the plaintext development payload format used until M2; JVM crypto is the JDK plus BouncyCastle.

v0.2 changes: envelope split into `public` and `encrypted` (3.1, 3.2); optimistic loan returns and `loan.dispute` / `loan.resolve` (3.3); policy corrections C1-C13 with tests (2.1); ADR folder renamed to `docs/adr/`.

Reference: the trust model now lives in `shared/src/commonMain/kotlin/circles/policy/CircleAccess.kt` (v0.2, implements C1-C13) with tests in `shared/src/commonTest`; they pass on the JVM and on Node/JS. The original sketch is kept as `docs/spec/CircleAccess.v0.1.kt.orig` for comparison.

## 0. Ground rules

- Language: Kotlin (2.x) for server and shared logic. Gradle Kotlin DSL.
- Keep dependencies few. No external services required to run. SQLite only.
- No custom cryptography. Use libsodium primitives only (see section 5).
- Every behaviour in this spec needs a test. Prefer pure functions in `shared` with property-based tests where useful.
- Do not add telemetry or third-party network calls of any kind.
- Commit in small steps. Each milestone ends with all tests green and docs updated.

## 1. Repository layout

```
circles/
  settings.gradle.kts
  build.gradle.kts
  shared/            Kotlin Multiplatform (jvm, js IR). Model, encoding, validation, policy, sync logic
  server/            Ktor JVM app. API, SQLite store, static hosting of web/
  web/               PWA (Vite + TypeScript + Preact). Consumes shared via Kotlin/JS
  deploy/            Dockerfile, docker-compose.yml, Caddyfile, systemd unit, Raspberry Pi notes
  docs/              operator guide, security note, adr/ (ADRs), API reference
  tools/             dev scripts (seed data, load test)
```

Decide: Compose Multiplatform for Web is optional later; v1 UI is Preact + TypeScript for size and accessibility control.

## 2. Domain model (shared)

Identifiers: `MemberId`, `DeviceId`, `CircleId`, `ItemId`, `EventId` as value classes over strings. `EventId` is lowercase hex of BLAKE2b-256 over the canonical bytes of the signed envelope fields (section 3).

Enums:
- `Tier`: NEWCOMER(0), NEIGHBOUR(1), TRUSTED(2), STEWARD(3). Derived, never persisted.
- `ItemClass`: ESSENTIAL, EVERYDAY, VALUABLE, HIGH_VALUE. Default minimum tiers: NEWCOMER, NEWCOMER, NEIGHBOUR, TRUSTED.
- `EventType` (plaintext on envelope): see section 3.3.

Circle config (plaintext inside an encrypted `config.update` payload; defaults below):
- thresholds: NEIGHBOUR 3.0, TRUSTED 10.0, STEWARD 25.0 (STEWARD tier is advisory; actual steward role is granted by `steward.add`). Must be strictly increasing (C5).
- halfLifeDays: 180
- vouchesForNeighbour: 2, voucherMinTier: NEIGHBOUR, vouchValidityDays: 180 (caps the author's `expiresDay`, C1)
- reviewersForPause: 3, pauseReviewerMinTier: TRUSTED, defaultPauseDays: 30
- returnGraceDays: 7, promptConfirmDays: 3, damageClaimDays: 30 (3.3)
- incidentCooldownDays: 90 (C7)
- maxCreditedPerCounterpart: 3, counterpartWindowDays: 90 (C8)
- recoveryThreshold k: 2, steward count n: 3 minimum
- event weights (map from `EventKind` to points; defaults in `CircleAccess.kt`)

Policy (implemented in `shared/policy`):
- `StandingCalculator.compute(member, config, events, vouches, tierOf, today)`: exponential decay in integer days, clamp at zero, per-counterpart credit cap, vouch on-ramp, incident cooldown, reasons list.
- `StandingCalculator.computeAll(...)`: the two-pass tier computation (base tiers without vouching, then with vouching using the vouchers' base tiers). A member vouched in to NEIGHBOUR cannot vouch for others until they earn NEIGHBOUR through points. Document and test this.
- `AccessPolicy.canBorrow(...)`: returns `Eligible | NotYet(howToUnlock) | Paused | OwnItem`. Essentials always eligible. Owner can raise the minimum tier for their item, never lower it.
- Add `EventKind` weights as config (defaults in `CircleAccess.kt`), stored in `config.update`.
- Config comes from events and is untrusted: run `CircleConfig.validate()` at fold time and fall back to defaults if it reports issues.

### 2.1 Policy corrections to the v0.1 sketch

Each row is a defect found in review of the v0.1 sketch, the fix now in `CircleAccess.kt`, and the test that must exist in `shared/jvmTest`.

| ID | Defect in v0.1 | Fix | Test |
|---|---|---|---|
| C1 | Vouches were counted with `count`, so one voucher vouching twice gave two vouches. Future-dated vouches and self-vouches counted. An author could set any `expiresDay`. | Count distinct vouchers. Ignore vouches dated after today and self-vouches. Effective expiry is `min(expiresDay, day + vouchValidityDays)`. | Alice vouches for Cal twice: Cal stays NEWCOMER. Alice and Bea vouch: Cal is NEIGHBOUR. Vouch dated tomorrow is ignored. Vouch with `expiresDay` far in the future expires at `day + vouchValidityDays`. Self-vouch is ignored. |
| C2 | `sponsorPenalty` used `now` for the event time and vouch liveness, so folding on different days gave different histories. | `sponsorNudges` uses `event.day` for both the nudge time and vouch liveness, and nudges each voucher once. | Same inputs folded with `today` = day 100 and day 101 give nudges with the same `day`. A vouch that had expired by the incident day produces no nudge. Two vouches from one voucher produce one nudge. |
| C3 | The owner could borrow their own item (and the demo only "worked" because of it). | `canBorrow` returns `OwnItem` when borrower is the owner. | Owner asking for their own HIGH_VALUE item gets `OwnItem`, not `Eligible` or `NotYet`. |
| C4 | `howToUnlock` always offered the vouch route, though vouching only opens NEIGHBOUR. A stray string interpolation was left in. | The vouch route is listed only when the item needs NEIGHBOUR or lower. | NEWCOMER asking for a VALUABLE item sees the vouch route. NEWCOMER asking for a HIGH_VALUE item does not. |
| C5 | Thresholds that were not increasing could yield a tier whose lower thresholds were not met. | `CircleConfig.validate()` returns issues for non-increasing or missing thresholds and non-positive durations or counts. `validatedOrDefault()` falls back to defaults. | Thresholds NEIGHBOUR 10, TRUSTED 3 are reported invalid and fall back to defaults. Each non-positive duration or count is reported. Defaults are valid. |
| C6 | The demo used 12 weekly-ish events, so "established" Alice was only NEIGHBOUR. | The demo uses enough events that Alice and Bea are TRUSTED, and shows the own-item and cooldown cases. | The demo's expectations are encoded as tests: Alice TRUSTED, Cal NEIGHBOUR via vouches, Dee NEWCOMER. |
| C7 | Clamping at zero meant the "only sizeable negative" cost a low-standing member nothing. | An unreported incident starts a cooldown (`incidentCooldownDays`): VALUABLE and HIGH_VALUE items are on hold, essentials and everyday items stay open. Points are still clamped. The reason text says it is a pause, not a ban, and when it ends. | NEWCOMER with an unreported incident yesterday: ESSENTIAL and EVERYDAY eligible, VALUABLE `NotYet` with days remaining. After `incidentCooldownDays`, the hold is gone. A TRUSTED member with an incident is also on hold for VALUABLE. |
| C8 | Two accomplices could farm points by repeatedly lending each other trivial items. | Positive events with a counterpart are credited at most `maxCreditedPerCounterpart` times per `counterpartWindowDays`, taking events in day order. | 10 positive events with the same counterpart inside the window credit only 3. Events with 10 different counterparts all credit. Result is the same for any event order. |
| C9 | Any three reviewers (even newcomers) could place a pause, and the duration was hard-coded. | `tryPause` counts only reviewers at `pauseReviewerMinTier` or above, excludes the subject, uses `defaultPauseDays`, and returns a sealed `PauseResult`. | Three NEWCOMER reviewers: `Rejected`. Three TRUSTED reviewers: `Placed` with `defaultPauseDays`. Subject among reviewers is not counted. Blank reason: `Rejected`. |
| C10 | `java.time` and `String.format` are not available in Kotlin/JS. Floating point could differ between JVM and JS at a threshold. | Integer days throughout. A `format1` helper. Points rounded to two decimals before tier comparison. | Points exactly at a threshold after rounding give the higher tier. `format1(3.0)` is "3.0" and `format1(-0.5)` is "-0.5". The same test vectors pass on JVM and JS. |
| C11 | The config lacked the spec's fields; weights were not configurable. | `CircleConfig` has all fields in section 2 and a `weights` map. | A config with a changed weight changes points. A config with a missing weight key falls back to the enum default. |
| C12 | The tier snapshot lived in the demo. | `computeAll` in the library, with the rule documented. | A voucher who is only NEIGHBOUR through vouches does not count as a valid voucher. A voucher with NEIGHBOUR points does. |
| C13 | No kind for prompt owner confirmation. | New `EventKind.CONFIRMED_RETURN_PROMPTLY` (+0.25). | The kind is credited with the configured weight and decays like others. |

Existing tests in this section (decay at 0, 1 and 2 half-lives, clamp at zero, and so on) still apply and now use integer days.

Tests (shared/jvmTest): decay at 0, 1 and 2 half-lives; clamp at zero; vouch expiry; vouch from below `voucherMinTier` ignored; late return recoverable; essentials open during pause; owner cannot lower class minimum; deterministic results for the same event set in any order.

## 3. Events and envelope

### 3.1 Envelope (plaintext; signed)

```
Envelope {
  v: Int                    // format major version, currently 1
  circle: CircleId
  author: MemberId
  device: DeviceId
  type: EventType
  day: Int                  // days since 1970-01-01 UTC (rounded date, untrusted claim)
  ctr: Long                 // per-device monotonic counter
  epoch: Int                // circle key epoch the event concerns (0 for bootstrap types)
  public: Map                // readable, signed, NOT encrypted: only what the server must read (see 3.2). May be empty
  nonce: Bytes(24)          // empty when encrypted is empty
  encrypted: Bytes          // the secret part: XChaCha20-Poly1305 ciphertext of the payload (see 3.2); empty if the type has none; max 64 KiB
  sig: Bytes(64)            // Ed25519 over canonical bytes of all fields above except sig
}
```

- Canonical bytes: deterministic CBOR (RFC 8949 section 4.2), definite lengths, map keys sorted bytewise, prefixed with the domain string `"circles/event/v1"`.
- `id = hex(BLAKE2b-256(canonical bytes including sig))`. The server also assigns `serverSeq` (per circle, monotonic) on acceptance. `serverSeq` is not signed.
- Server validation on push: size limits; `v` supported; circle exists; device registered and not revoked as of this event; signature valid; `day <= receivedDay + 1`; a duplicate id returns "duplicate", not an error (`ctr` is informational and may repeat, ADR 0011); `type` allowed for the author's role (e.g. only stewards may send `steward.add`, `config.update`, `key.rotate`). The server derives the device list from the `public` part of `circle.create`, `member.join`, `device.add` and `device.revoke`, and the steward set from `circle.create`, `steward.add` and `steward.remove`. These checks keep spam out; they are not a security boundary, because the operator is not trusted. Clients re-validate every event they fold (3.4).

### 3.2 Payload: `public` and `encrypted`

Each event carries two CBOR maps specific to its type:

- `public`: readable by anyone who holds the event, including the server. It is signed (part of the canonical bytes) but not encrypted.
- `encrypted`: the secret part, encrypted with the circle key at `epoch`. Associated data = canonical envelope bytes without `encrypted` and `sig`, which includes `public`, so `public` cannot be swapped onto another event's encrypted part.

Placement rule: a field goes in `public` only if the server enforces a rule with it, or the event must be readable by someone who does not yet hold the circle key. Everything else goes in `encrypted`. When in doubt, use `encrypted`.

| Type | `public` | `encrypted` |
|---|---|---|
| `circle.create` | founder member id; founder device id and public keys (signing, box); the founder is the first steward | none |
| `member.invite` | invite public key, `expiresDay`, `maxUses` | optional note |
| `member.join` | member id; device id and public keys; invite proof (signature by the invite key over the join) | none (display name comes later in `member.profile`) |
| `device.add` | member id; device id and public keys | device label |
| `device.revoke` | device id | reason |
| `steward.add` / `steward.remove` | target member id | note |
| `key.wrap` | target device id; epoch wrapped; the wrapped key (a sealed box to that device, safe in plaintext, and a new device has no circle key to decrypt with) | none |
| `key.rotate` | new epoch | none |
| every other type | empty | the whole payload |

Consequences:
- Bootstrap types need no special case: the first `device.add` is the same as any other.
- Vouch targets, loans, items, standing events, config and notes are in `encrypted`. The server cannot see who vouches for whom. (The design doc said it could; that is corrected in v0.2.)
- The operator sees the steward set, device registrations and which device is wrapped to which epoch. The security note must say so.
- For types with an empty `encrypted`, `epoch` is the epoch the event concerns (0 for bootstrap).

### 3.3 Event types (v1)

| Type | Author | Purpose |
|---|---|---|
| `circle.create` | founder | Creates circle; registers founder device and first steward |
| `member.invite` | member | Invite with a public invite key; secret travels in link fragment |
| `member.join` | invitee | Redeems invite, registers first device key, pending until keys are wrapped |
| `member.profile` | member | Display name, contact preferences, coarse area |
| `device.add` / `device.revoke` | member or steward | Register or revoke a device |
| `key.wrap` | member or steward | Circle key (epoch N) wrapped to a device |
| `key.rotate` | steward | New epoch; wraps to all current devices |
| `recovery.share` | steward | Shamir share of circle key wrapped to a steward device |
| `steward.add` / `steward.remove` | steward | Steward changes (quorum rules below) |
| `config.update` | steward | Circle config |
| `vouch` | member | Vouch for a member; has `expiresDay` |
| `item.upsert` / `item.retire` | owner | Item public fields; detail key id |
| `item.detail` | owner | Sensitive fields encrypted with item detail key |
| `skill.upsert` / `skill.retire` | member | Skill offer |
| `loan.request` | borrower | Request an item for a window |
| `loan.respond` | owner | Approve or decline; approval wraps detail key to borrower devices |
| `loan.handover` | owner or borrower | Confirms hand-over |
| `loan.return` | borrower | Borrower reports the outcome: OK, LATE, DAMAGED_REPORTED, LOST_REPORTED. Counts automatically unless disputed (optimistic return, below) |
| `loan.dispute` | owner | Disputes a return: within `returnGraceDays` of the return, or within `damageClaimDays` for damage found later. Withholds credit pending review |
| `loan.resolve` | steward (not a party) | Resolves a dispute with an outcome |
| `standing.note` | member | Contribution events: SKILL_SHARE_HELPED, HOSTED_OR_ORGANISED, MENTORED_NEWCOMER (confirmed by the other party via `standing.confirm`) |
| `standing.confirm` | counterpart | Confirms a `standing.note`, or a `loan.return` (owner) |
| `pause.propose` / `pause.review` / `pause.lift` / `pause.appeal` | member / steward | Pause lifecycle; takes effect only when `reviewersForPause` distinct reviewers (not the subject) have reviewed |

Rules:
- Standing notes count only when the other party confirms (`standing.confirm`).
- A loan is real only through two-party steps: `loan.request`, owner `loan.respond` (approve), then `loan.handover` confirmed by both parties. Only a loan with a confirmed hand-over can earn return credit, so a fake loan needs the owner's cooperation.
- **Optimistic return.** For such a loan, the borrower's `loan.return` counts with its reported outcome on the earlier of: the owner's `standing.confirm`, or `returnGraceDays` (default 7) after the return's `day` with no `loan.dispute`. Owner silence is never penalised and never blocks the borrower. LATE is judged against the window agreed in `loan.respond`.
- An owner who confirms within `promptConfirmDays` (default 3) earns `CONFIRMED_RETURN_PROMPTLY` (+0.25). There is no negative for the owner.
- **Dispute.** `loan.dispute` withholds return credit for both parties ("pending review" in the UI) until a steward who is not a party sends `loan.resolve`. Decide in an ADR whether resolution needs more than one steward; default is one non-party steward, and the UI flags a circle with a single steward.
- Honest reporting of damage or loss (`*_REPORTED`) carries no penalty. Unreported damage found later (disputed within `damageClaimDays`, default 30) is the only large negative. It also starts the incident cooldown (2.1, C7).
- Sponsor nudge: when an unreported-loss event lands for a vouchee, sponsors with live vouches (live on the incident's `day`) receive a mild late-return-equivalent entry, computed locally at fold time with the incident's `day` (not a separate event, C2).

### 3.4 Folding

`shared` exposes `fold(events) -> CircleState` (members, devices, items, skills, loans, vouches, pauses, standing inputs). Folding must be deterministic and order-insensitive where events commute; where order matters (config, key epochs) use `serverSeq` then `id` as tie-breaker. Ignore events the local validator rejects (bad signature, unauthorised type) and surface a debug list in the UI.

## 4. Server

### 4.1 Stack

Ktor (Netty), kotlinx.serialization, SQLite via `org.xerial:sqlite-jdbc` with WAL mode. One process. No ORM; hand-written SQL with a small repository layer. Static hosting of `web/dist` from the same process.

### 4.2 Schema (initial migration `V1__init.sql`)

```sql
CREATE TABLE schema_version (version INTEGER NOT NULL);
CREATE TABLE circles (
  id TEXT PRIMARY KEY, created_day INTEGER NOT NULL
);
CREATE TABLE devices (
  device_id TEXT PRIMARY KEY, circle_id TEXT NOT NULL, member_id TEXT NOT NULL,
  sign_pub BLOB NOT NULL, box_pub BLOB NOT NULL,
  added_seq INTEGER NOT NULL, revoked_seq INTEGER
);
CREATE TABLE events (
  server_seq INTEGER NOT NULL, circle_id TEXT NOT NULL, event_id TEXT NOT NULL, v INTEGER NOT NULL,
  author TEXT NOT NULL, device TEXT NOT NULL, type TEXT NOT NULL,
  day INTEGER NOT NULL, ctr INTEGER NOT NULL, epoch INTEGER NOT NULL,
  public BLOB NOT NULL,       -- canonical CBOR of the public map (may be empty)
  nonce BLOB NOT NULL, encrypted BLOB NOT NULL, sig BLOB NOT NULL, received_at INTEGER NOT NULL,
  PRIMARY KEY (circle_id, server_seq),
  UNIQUE (circle_id, event_id)
);
CREATE INDEX events_by_device_ctr ON events (device, ctr);   -- not unique (ADR 0011)
CREATE TABLE blobs (
  hash TEXT PRIMARY KEY, circle_id TEXT NOT NULL, size INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);   -- blob bytes on disk under data/blobs/<aa>/<hash>
CREATE TABLE sessions (
  token_hash BLOB PRIMARY KEY, device_id TEXT NOT NULL, expires_at INTEGER NOT NULL
);
CREATE TABLE challenges (
  nonce BLOB PRIMARY KEY, device_id TEXT NOT NULL, expires_at INTEGER NOT NULL
);
```

The migration also creates `stewards(circle_id, member_id, added_seq, removed_seq)` and `invites(circle_id, event_id, author, invite_pub, expires_day, max_uses, used)`, which the server needs to enforce roles and invite limits (ADR 0014).

The server keeps device public keys in `devices` by reading the `public` part of `circle.create`, `member.join`, `device.add` and `device.revoke` envelopes (see 3.2). It also tracks the steward set from the `public` part of `circle.create`, `steward.add` and `steward.remove`, for the role checks in 3.1.

### 4.3 API (v1, JSON over HTTPS, all under `/api/v1`)

Auth:
- `POST /auth/challenge` `{deviceId}` -> `{nonce}` (expires in 60 s)
- `POST /auth/session` `{deviceId, nonce, sig}` -> `{token, expiresIn}` (opaque token, 12 h; stored hashed)

Sync:
- `POST /circles/{id}/events` body `{events:[Envelope...]}` (max 200 per call) -> `[{id, status: accepted|duplicate|rejected, serverSeq?, reason?}]`
- `GET /circles/{id}/events?after={seq}&limit=500` -> `{events:[{serverSeq, envelope}], next, hasMore}`
- `GET /circles/{id}/events/stream` SSE: `data: {serverSeq}` hints; clients then pull. Optional; clients must work without it.

Blobs:
- `PUT /circles/{id}/blobs/{hash}` ciphertext (<= 2 MiB); server verifies hash of body; `GET` same path.

Joining and admin:
- `POST /circles` accepts one signed `circle.create` envelope, `{event}`; no session (the founder has none yet). Open by default; `CIRCLES_ALLOW_CREATE=false` closes it (ADR 0014). Answers `201` accepted, `200` duplicate, `422` rejected.
- `POST /circles/{id}/join` accepts a signed `member.join` envelope plus invite proof (public join path; rate limited).
- `GET /healthz`, `GET /readyz`, `GET /version` (unauthenticated, minimal info).
- `POST /admin/import` and `GET /admin/export/{circleId}`: authorised by a signed steward request, not by a server password (see 4.5).

Errors: RFC 9457 problem+json. No stack traces. Rate limits: auth 10/min/IP, push 60/min/device, join 5/min/IP.

### 4.4 Visibility

The server filters by circle membership only: an authenticated device receives events for circles it belongs to. It cannot filter by tier (it never sees standing). Sensitive item details are protected cryptographically by per-item detail keys, not by server rules.

### 4.5 Export, import, backup (the handover feature)

CLI: `circles-server serve | migrate | backup <dir> | restore <file> | export <circleId> <file> | import <file> | verify <file> | bootstrap`.
- `backup`: uses the SQLite online backup API, plus blobs, into a single archive with a manifest and checksums. Runs automatically before any migration and optionally on a schedule (`CIRCLES_BACKUP_CRON`).
- `restore`: validates the archive, refuses to overwrite a non-empty data directory unless `--force`.
- `export`: a circle's complete event log and blobs in a documented, versioned format (`circles-export/1`): newline-delimited JSON envelopes plus a manifest. Contains only ciphertext and public keys.
- `import`: re-validates every signature and rebuilds `devices` and sequence numbers in log order. Idempotent.
- Because export and import need no server secret, any steward (or anyone holding a replica) can seed a new host.
- Member clients can export their local replica in the same format (`Settings -> Export circle`).

Authentication on a new host requires nothing from the old host: devices sign challenges with keys already in the log.

## 5. Cryptography (shared)

Use libsodium: `libsodium-wrappers` (browser) and a JVM binding such as `lazysodium-java` (decide; record in ADR). Define a `Crypto` interface in `shared` with `expect/actual` implementations.

- Signatures: Ed25519 (`crypto_sign_detached`).
- Hash: BLAKE2b-256 (`crypto_generichash`).
- Symmetric AEAD: XChaCha20-Poly1305 IETF (`crypto_aead_xchacha20poly1305_ietf_*`), random 24-byte nonces.
- Key wrapping: sealed box (`crypto_box_seal`) to a device's X25519 key.
- Secret sharing: Shamir over GF(256), k-of-n, applied to the 32-byte circle key. Implement in `shared` with test vectors, or use a vetted library (decide; ADR). This is the one piece where a mistake is serious: write exhaustive tests (all k-subsets reconstruct; k-1 yields nothing useful).
- Key storage in the browser: IndexedDB. Where available, wrap the private keys with a key derived from a WebAuthn PRF output (passkey); otherwise store with a user passphrase option. Never log keys. Document that browser storage can be cleared by the user agent.
- Recovery code (optional per member): 24-word encoding of a seed that can regenerate the device identity. Decide wordlist; test round-trip.

Detail keys: per item, random 256-bit key; `item.detail` encrypted with it; `loan.respond` (approval) includes the detail key sealed to each of the borrower's devices.

Rotation: `key.rotate` creates epoch N+1, wraps to all current devices (excluding revoked). Old epochs remain for reading history. Rotation is required when a member leaves or a device is revoked.

## 6. Web client (PWA)

Stack: Vite, TypeScript, Preact, minimal CSS (no heavy UI framework). Shared Kotlin/JS exposed via `@JsExport` facade; keep the facade narrow (fold, policy, crypto, encode/decode).

Local storage: choose IndexedDB (via a thin wrapper) or SQLite-WASM on OPFS; decide on measured bundle size and browser support. Store raw envelopes plus a derived-state cache that can always be rebuilt.

Sync engine: on open, on `online` event, after local writes (debounced), and on SSE hint: push outbox, then pull from `lastServerSeq`. Retry with backoff. Surface state as "up to date / syncing / offline, N changes waiting". Background Sync API where available, never required.

Core screens (v1):
1. Join / sign in (invite link flow, device naming, passkey or passphrase)
2. Circle home (what's shared nearby, open requests, recent activity)
3. Items (list, detail, add/edit, QR tag printing)
4. Skills (search is local and client-side)
5. Borrow flow (request -> owner approves -> hand-over -> return, with explicit "reported damage" option that carries no penalty)
6. My standing (tier, points, plain-language reasons, how to unlock more)
7. Members and vouching (invite, vouch, in-person verification via QR/fingerprint)
8. Stewards (config, pauses, key rotation, recovery shares, export)
9. Settings (devices, export circle, language, accessibility, delete my data on this device)

UI rules:
- Show the reasons and "how to unlock" text from `AccessDecision`. Never show a numeric ranking of members to other members; show only your own standing and tier labels.
- Essentials are always shown as borrowable.
- Pause screens name the appeal route and expiry.

Service worker: cache app shell and static assets with versioned cache; update prompt on new version; never cache API responses beyond the local replica.

Budgets: initial JS <= 250 KB gzip; first load usable on a mid-range phone on 3G-class speeds; offline cold start works after first successful load.

Accessibility acceptance: WCAG 2.2 AA checks with axe in CI; manual screen-reader pass (TalkBack and VoiceOver) on the borrow flow; full keyboard operability; reading-level check on UI copy; respects `prefers-reduced-motion` and high-contrast settings.

## 7. Configuration and operations

Environment variables (all optional unless stated):

| Variable | Default | Meaning |
|---|---|---|
| `CIRCLES_DATA_DIR` | `./data` | Database, blobs, backups |
| `CIRCLES_BASE_URL` | required for prod | Public URL; used in invite links |
| `CIRCLES_BIND` | `0.0.0.0` | Listen address |
| `CIRCLES_PORT` | `8080` | Listen port |
| `CIRCLES_LOG_LEVEL` | `info` | Log level; JSON logs to stdout |
| `CIRCLES_BACKUP_CRON` | unset | Scheduled backups |
| `CIRCLES_BACKUP_KEEP` | `7` | Backups to retain |
| `CIRCLES_SMTP_*` | unset | Optional email notifications |
| `CIRCLES_TRUST_PROXY` | `false` | Honour `X-Forwarded-*` from a reverse proxy |

TLS: document Caddy as the first-class option (`deploy/Caddyfile`). Do not build ACME in v1.

Deliverables in `deploy/`: Dockerfile (multi-stage, non-root, read-only root fs compatible), `docker-compose.yml` (server + Caddy), systemd unit, Raspberry Pi notes.

Docs deliverables in `docs/`:
1. `operator/requirements.md`: minimum CPU, RAM, disk, bandwidth, measured by a load test with a 300-member synthetic circle (fill in numbers from measurements, not guesses).
2. `operator/quickstart-*.md`: Docker Compose, plain VPS, Raspberry Pi/home server.
3. `operator/backup-restore.md`, `operator/upgrade.md`, `operator/handover.md` (step-by-step for a new host and for a departing steward).
4. `security.md`: what the server can and cannot see, threat model, how to verify members in person.
5. `api.md`: generated or hand-written API reference.
6. `adr/*.md`: ADRs, with `adr/README.md` index and `adr/template.md`.

## 8. Testing and acceptance

Unit and property tests in `shared` (policy, folding, encoding, crypto wrappers, Shamir), including:
- Policy corrections C1-C13 (section 2.1).
- `public`/`encrypted`: changing any `public` field invalidates the signature; moving a `public` onto another event's `encrypted` fails AEAD authentication; a server dump contains only the `public` fields listed in 3.2.
- Optimistic return: a return with no dispute counts after `returnGraceDays`, not before; owner confirmation counts it immediately and earns `CONFIRMED_RETURN_PROMPTLY` only within `promptConfirmDays`; a dispute withholds credit for both until `loan.resolve`; a return without a confirmed hand-over earns nothing; owner silence produces no negative entry.

Server integration tests (JVM, ephemeral SQLite):
- Push/pull round-trip; duplicate pushes are idempotent; rejected types by role; bad signature rejected; future day rejected; `serverSeq` monotonic; two devices pushing concurrently.
- Auth challenge expires; replay of a used nonce fails.

End-to-end tests (Playwright against a local server + built PWA):
- Join via invite on a fresh browser profile; pending state until keys are wrapped; then access.
- Offline: go offline, create an item and a borrow request, come back online, verify sync and owner approval flow.
- Two clients request the same item offline; owner resolves; both end consistent.

**Handover acceptance tests (must pass before M5 is done):**
1. Create circle with 3 stewards and 5 members. Take a backup, destroy the host, restore on a new host. Existing devices reconnect without re-enrolment and see full history.
2. Destroy the host with no backup. Export from one member's local replica, import on a new host. Same outcome.
3. Remove a steward; verify key rotation; the removed steward's device cannot decrypt new events.
4. Lost device: steward re-wraps the circle key to the new device after verification; history readable.
5. Any 2 of 3 stewards reconstruct the circle key from recovery shares; 1 cannot.

Security tests: fuzz envelope decoding; check that logs contain no keys or plaintext payloads; confirm the server cannot decrypt payloads (no key material in the database dump).

Load test: 300 members, 20k events, sync from zero in under 10 seconds on the target minimum hardware (record results in `docs/operator/requirements.md`).

## 9. Milestones and tasks

**M0 Skeleton**: repo, Gradle multi-module, CI (build, tests, lint), ADR template. Port `CircleAccess.kt` into `shared/policy`, fix compile errors, add the tests in section 2. Done when `./gradlew check` passes. (Done 2026-11-02.)

**M1 Event log and sync (plaintext payloads, signatures real)**: envelope encoding, signing, id, validation; SQLite store; push/pull endpoints; auth challenge flow; `circle.create`, `member.invite`, `member.join`, `device.add`. CLI `serve` and `migrate`. Done when two JVM test clients sync a circle. (Done 2026-11-02, see ADRs 0010-0014; `TwoClientsSyncTest`.)

**M2 Encryption**: libsodium integration, circle key, payload encryption/decryption, key wrapping, `key.rotate`, item detail keys. Done when a database dump contains no plaintext payloads.

**M3 PWA offline**: Kotlin/JS facade, local replica, outbox, sync engine, screens 1-6, service worker. Done when the offline E2E tests pass.

**M4 Community mechanics**: vouching, steward screens, pauses with quorum, config updates, QR verification, key rotation on removal, recovery shares and steward-assisted rewrap, standing confirmation flow. Done when handover tests 3-5 pass.

**M5 Operations**: backup, restore, export, import, verify; Dockerfile and compose; docs; load test numbers; handover tests 1-2. Done when all acceptance tests pass and docs are reviewed by someone who has not seen the code.

**M6 Accessibility and pilot**: axe in CI, screen reader pass, reading-level review, pilot with one real group of 10-20 people; collect issues and adjust thresholds. 

**Later**: discovery federation; Android wrapper reusing `shared`; optional per-tier keys; built-in ACME.

## 10. Non-goals for v1

Currency, credits, balances, payments, or interest; global or cross-circle reputation; ActivityPub federation; end-to-end defence against a malicious operator beyond in-person verification; push-only notifications; multi-circle-per-server administration UI (the data model supports multiple circles, but v1 UI manages one).

## 11. Open decisions to record as ADRs

1. libsodium binding for JVM; Shamir implementation (own with test vectors vs vetted library).
2. Local replica store in browser: IndexedDB vs SQLite-WASM/OPFS.
3. Kotlin/JS bundle size outcome; fallback plan if over budget (move only the UI-facing facade to TypeScript).
4. Recovery code wordlist and format.
5. Whether the server serves SSE in v1 or polling only.
6. Default thresholds and half-life after the pilot.

## 12. Definition of done (v1)

- All milestone acceptance criteria and handover tests pass in CI.
- A person who has never seen the project can deploy an instance on a Raspberry Pi or a small VPS using only `docs/operator/`, in under an hour.
- The security note accurately describes what the server can see, verified by inspecting a database dump.
- A screen-reader user can complete the borrow flow unaided.
