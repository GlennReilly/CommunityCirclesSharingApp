# 0011. Drop UNIQUE(device, ctr) on events

- Status: Accepted
- Date: 2026-11-02

## Context

The spec's schema made `(device, ctr)` unique and rejected a reused counter. Several browser tabs, or a device restored from a backup, can legitimately reuse a counter. Rejecting them would lose real events. The event id (hash of the canonical bytes) already makes pushing the same event twice harmless.

## Decision

Remove the `UNIQUE (device, ctr)` constraint and the "ctr not previously seen" validation rule. Idempotency comes from `UNIQUE (circle_id, event_id)`: a push of an event that is already stored returns `duplicate`. `ctr` stays in the signed envelope as an informational per-device counter (it must be a non-negative number) and gets a plain, non-unique index.

## Consequences

Two different events with the same `(device, ctr)` are both accepted. The server can no longer use `ctr` to spot gaps or forks; clients must not rely on it for ordering (they use `serverSeq`, then `id`). Spec sections 3.1 and 4.2 are amended to match.
