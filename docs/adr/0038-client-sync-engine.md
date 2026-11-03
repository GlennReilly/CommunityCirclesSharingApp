# 0038. Client sync engine: shared code, verify once at ingest

- Status: Accepted
- Date: 2026-11-03

## Context

ADR 0036 sets the rule that a browser verifies each event's signature once, when it arrives, and never again on open, because noble costs 2.4 ms per check. The client's fold (`CircleFold`) re-verified every signature on every run, so the rule could not be met. The sync logic (outbox, push, pull, retry, status) also has to be testable without a browser, and the spec (section 6) lists what it must do: push the outbox then pull from `lastServerSeq`, retry with backoff, and show "up to date / syncing / offline, N changes waiting".

## Decision

1. **The engine lives in `shared` (`circles.sync`, common Kotlin), not in the web module.** It sees the network only through `SyncApi` and storage only through `Replica`, so the same code and the same contract tests run on the JVM and in Node, and a future Android client can reuse it. The browser supplies `fetch` (an `HttpTransport`) and an IndexedDB `Replica`; `InMemoryReplica` is the reference implementation and `ReplicaContract` is the test the IndexedDB one must pass.
2. **`SyncEngine.sync(circle)`**: push the outbox in batches of at most 200, then pull pages until the server has no more. Accepted and duplicate events leave the outbox; events the server refuses leave it too and are kept in a `refused` list with the reason, never retried automatically. An own event comes back through the log and is stored like any other. Failures keep every change: offline, busy (429 or 5xx, with `Retry-After`), signed out (401 after one re-login) and other refusals each have their own `SyncState`. Scheduling (open, `online`, after a write, on an SSE hint) stays with the caller; `Backoff` gives the delay (2 s doubling to 5 minutes, spread by 20%, never below the server's `Retry-After`).
3. **`HttpSyncApi`** implements `SyncApi` over the HTTP API: it logs in by signing the challenge, reuses the token, logs in again once on 401, sends `circle.create` and `member.join` to their public endpoints (the device is not registered until they are accepted), batches everything else with the token, and turns a refusal of one public event into a rejected result for that event.
4. **Verify once at ingest.** For each pulled event the engine checks shape and size (`EnvelopeChecks`), that it belongs to this circle, computes its id, and verifies its signature against the key the log registered for that device (`DeviceKeyDirectory`: `circle.create`, `member.join` and `device.add` events whose own signature checked out; first registration of a device id wins, as in the fold). The key it was checked with is stored with the entry (`LogEntry.verifiedWith`). Malformed events or events of another circle are dropped and counted; events from a device not yet registered are stored unchecked and counted.
5. **The fold takes a `VerifiedSignatures` argument** (default none, which is what the server and every existing caller use). It skips the signature check for an event only if the recorded key is the same key it would verify with; otherwise it checks for itself. A wrong or stale record can therefore only cost time, never accept a forgery, and the fold keeps every other rule (registration, revocation, roles, invites, epochs) exactly as before. `Replica.fold` wires the stored keys into the fold.
6. **Not built:** folding from a saved state snapshot. `Replica.fold` still replays the whole stored log (without the signature work); the snapshot and incremental fold of ADR 0036 point 3 remain to do. The invite proof inside each `member.join` is still verified on every fold, one check per join.

## Consequences

- The expensive part of opening a circle (signature checks) is paid once per event, at ingest, and the measured cost is spread over the sync. A new device still pays 2.4 ms per event on its first sync.
- The fold's behaviour is unchanged when no record is given, and the full existing fold suite still runs that way; the new tests show that a recorded check for a different key does not skip a real check.
- A device that is revoked, signed out or refused sees a state it can show; a queued change is never lost by a failed sync.
- The server's own tests drive the engine through `HttpSyncApi` against the real rules (`SyncEngineEndToEndTest`), so the wire format and the engine are tested together.
- Sync of several circles and the SSE hint loop are not part of this ADR; they use `sync(circle)` as it stands.
