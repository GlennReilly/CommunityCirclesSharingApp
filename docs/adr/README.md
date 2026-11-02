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

## Open decisions still to record

From the spec, section 11. Record each as an ADR when it is decided:

1. libsodium binding for the JVM; Shamir implementation (own with test vectors vs a vetted library).
2. Local replica store in the browser: IndexedDB vs SQLite-WASM on OPFS.
3. Kotlin/JS bundle size outcome and the fallback plan if over budget.
4. Recovery code wordlist and format.
5. Whether the server serves SSE in v1 or polling only.
6. Default thresholds and half-life, after the pilot.
7. Whether dispute resolution needs more than one steward (default: one non-party steward).
8. Whether to keep `UNIQUE(device, ctr)` on events (multiple tabs and restored devices can reuse counters).
