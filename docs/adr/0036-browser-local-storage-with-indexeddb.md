# 0036. Browser local storage with IndexedDB, and verify once

- Status: Accepted
- Date: 2026-11-03

## Context

Spec section 6 leaves the browser's local store open: IndexedDB, or SQLite compiled to WebAssembly on the origin private file system (OPFS), "decided on measured bundle size and browser support". The client keeps raw envelopes (the source of truth) and a state cache that can always be rebuilt. I measured both options, on 2026-11-03, on an Intel Core i7-3770 (2012), in Chrome 156 (headless) and Node 24. One run each, so treat the figures as orders of magnitude.

| Measured | Result |
|---|---|
| SQLite WebAssembly, gzipped: the `.wasm` (406 KB), the JavaScript (179 KB), the OPFS helper (10 KB) | **595 KB**, 2.4 times the whole 250 KB budget |
| The package's own requirement for persistent storage | worker only, and the page must be served with `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp` (from its README) |
| IndexedDB, bundle cost | none (built in); 1.4 KB with the `idb` wrapper |
| IndexedDB, insert 16,000 events (600 bytes each) in 32 transactions of 500 | 1.3 s |
| IndexedDB, read all 16,000 back / one range of 100 / 2,000 random reads | 0.22 s / 3 ms / 92 ms |
| IndexedDB, one event written per transaction | 0.7 ms each |
| IndexedDB, 100 photos of 250 KiB written / read | 0.24 s / 0.12 s |
| IndexedDB, a 1 MiB state snapshot written / read | 9 ms / 4 ms |
| Verifying one event's Ed25519 signature, noble (pure JavaScript) | **2.4 ms** (39 s for 16,000 events) |
| Verifying one event's Ed25519 signature, libsodium (WebAssembly) | 0.15 ms (2.5 s for 16,000 events) |

## Decision

1. **IndexedDB, used directly from Kotlin/JS.** No SQLite, no wrapper library: a thin Kotlin layer over the native API with suspend functions. SQLite WebAssembly is rejected: it alone costs more than the whole budget, needs a worker and two special headers on every page, and nothing in our access pattern needs SQL (search over items and skills is done in memory from the state, as the spec already says).
2. **One database, `circles`, with these stores.** `events`: key `[circle, serverSeq]`, value the wire envelope exactly as the server sent it plus a `verified` flag. `state`: key `circle`, a versioned snapshot of the fold and the sequence number it covers; it is a cache and is discarded whenever its version changes. `blobs`: key `[circle, hash]`, downloaded ciphertext. `outbox`: events built here and not yet accepted by the server. `meta`: cursors and settings. Device secret keys live in a separate store as the key-storage decision (spec 5) will define, wrapped by a passkey or passphrase.
3. **Verify once, never again on open.** Because a signature check costs 2.4 ms in the browser, a client must not re-verify the log each time it starts. An event's signature and id are checked when it arrives and the result is stored with it; the state snapshot records how far the fold has run; opening the app loads the snapshot and folds only the events after it. A full re-fold happens only when the snapshot is missing, stale, or fails an integrity check, and then from the stored `verified` flags, re-verifying only events that lack one.
4. **Heavy work off the screen.** Verification and folding run in a Web Worker, in log order so partial progress is usable, with a progress display. A new device joining a long-lived circle sees a one-time "catching up" step.
5. **Writes** in pages of at most 500 events per transaction; a member's own event is written to the outbox in its own transaction before any network call.
6. **Persistence.** The client asks the browser for persistent storage (`navigator.storage.persist()`) and shows whether it was granted. Browsers may still evict storage, and some do after a period of disuse; the spec already says so. The local copy of the log and blobs can always be downloaded again; the device's secret keys cannot, so losing them means re-enrolling through the recovery code or a steward.
7. **Quota.** On `QuotaExceededError` the client evicts downloaded blobs first (they can be fetched again) and never events or keys.

## Consequences

- The storage layer adds almost nothing to the bundle and is fast enough by a wide margin: opening a circle of 16,000 events reads in about a quarter of a second, and a member's own events write in under a millisecond.
- **A cost of choosing noble over libsodium (ADR 0035) is now measured.** Checking 16,000 signatures from scratch is about 39 s on this 2012 processor (a modern phone is several times faster; I have not measured one) instead of 2.5 s. Rule 3 above and the worker make it a one-time, visible step for a new device in a large, old circle, and invisible for a pilot-sized one (1,000 events is under 3 s here). If that is not acceptable, the options are: load libsodium lazily only to verify the first sync, or use the browser's native Ed25519 where available. Either would need its own ADR.
- Schema changes are written by hand in IndexedDB upgrade handlers, with the state cache simply dropped on a version change.
- There is no ad-hoc querying of the local data; features that need it build their own in-memory indexes from the state.
- Safari and Firefox were not benchmarked here; the IndexedDB numbers are Chrome's.
- Settles open decision 2 of the spec (the browser local store). ADR 0035 is not changed; its trade-off is recorded here.
