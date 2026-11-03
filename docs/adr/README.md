# Architecture decision records

One short file per decision that is hard to reverse, changes a rule, picks a technology, or answers a question the project says to ask first. Copy [template.md](template.md), number it `NNNN-slug.md`, and add it below.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-kotlin-multiplatform-and-ktor.md) | Kotlin Multiplatform shared module, Ktor server, SQLite | Accepted |
| [0003](0003-append-only-signed-event-log.md) | Append-only signed event log | Accepted |
| [0004](0004-public-and-encrypted-event-parts.md) | Events have a `public` and an `encrypted` part | Accepted |
| [0005](0005-tiers-are-advisory.md) | Tiers are advisory; owners decide | Accepted |
| [0006](0006-optimistic-returns.md) | Optimistic returns with owner dispute | Accepted |
| [0007](0007-integer-days-and-fixed-point-points.md) | Integer days and rounded points in policy code | Accepted |
| [0008](0008-incident-cooldown.md) | Incident cooldown instead of negative standing | Accepted |
| [0009](0009-web-ui-stack.md) | Web UI stack | Proposed |
| [0010](0010-jvm-crypto-jdk-and-bouncycastle.md) | JVM crypto for M1: JDK Ed25519 and BouncyCastle BLAKE2b | Accepted |
| [0011](0011-drop-unique-device-counter.md) | Drop UNIQUE(device, ctr) on events | Accepted |
| [0012](0012-canonical-encoding-and-event-identity.md) | Canonical encoding, event identity and wire format | Accepted |
| [0013](0013-identifiers-and-self-registering-events.md) | Identifiers and self-registering events | Accepted |
| [0014](0014-m1-server-rules-and-dev-payload-format.md) | M1 server rules and the plaintext development payload format | Accepted (rate limiting part superseded by 0015, payload format by 0024) |
| [0015](0015-rate-limiting-pulled-forward.md) | Rate limiting pulled forward from M5 | Accepted |
| [0016](0016-structured-json-logging.md) | Structured JSON logging without a logging framework | Accepted |
| [0017](0017-shadow-jar-for-single-file-deploy.md) | One runnable jar with the Shadow plugin | Accepted |
| [0018](0018-m2-libsodium-bindings.md) | M2 libsodium bindings: lazysodium (test scope) on JVM, libsodium-wrappers on JS | Accepted |
| [0019](0019-cipher-separate-from-crypto.md) | Encryption lives in a separate `Cipher` interface (amends 0018 point 1) | Accepted |
| [0020](0020-shamir-own-implementation.md) | Shamir secret sharing: own implementation with independent vectors | Accepted |
| [0021](0021-sealed-envelope-v1-and-circle-key-wrapping.md) | Sealed envelope v = 1, circle keys and key wrapping | Accepted (v = 0 flag removed by 0024) |
| [0022](0022-steward-changes-without-a-quorum.md) | Steward changes: any steward acts, the last steward stays | Superseded by 0023 |
| [0023](0023-steward-changes-need-a-quorum.md) | Steward changes need a quorum of distinct stewards | Accepted |
| [0024](0024-retire-the-plaintext-v0-format.md) | Retire the plaintext v = 0 format (amends 0014, 0021) | Accepted |
| [0025](0025-item-and-loan-event-payloads.md) | Item and loan event payloads and folding rules | Accepted |
| [0026](0026-sse-hints-and-blob-storage.md) | Server-sent event hints and blob storage | Accepted |
| [0027](0027-steward-quorum-setting-and-inactivity-fallback.md) | Steward quorum as a circle setting, with an inactivity fallback (supersedes 0023 once accepted) | Proposed |
| [0028](0028-client-image-policy.md) | Client image policy: resize, re-encode, strip metadata | Proposed |

## Open decisions still to record

From the spec, section 11. Record each as an ADR when it is decided:

1. ~~libsodium binding for the JVM in M2~~: decided (ADR 0018). Shamir: decided (ADR 0020).
2. Local replica store in the browser: IndexedDB vs SQLite-WASM on OPFS.
3. Kotlin/JS bundle size outcome and the fallback plan if over budget.
4. Recovery code wordlist and format.
5. ~~Whether the server serves SSE in v1 or polling only.~~ Decided: hint-only SSE, optional for clients (ADR 0026).
6. Default thresholds and half-life, after the pilot.
7. Whether dispute resolution needs more than one steward (default: one non-party steward).
8. ~~Whether to keep `UNIQUE(device, ctr)`~~: decided, dropped (ADR 0011).
