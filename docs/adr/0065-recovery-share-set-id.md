# 0065. A recovery share names its set

- Status: Accepted
- Date: 2026-11-04
- Amends ADR 0048 (how the fold tells one set of shares from the next); it does not supersede it.

## Context

ADR 0048 makes one set of shares per steward change or key rotation, each steward's share sealed to each of their devices. The fold has to know which shares belong to the newest set, to tell a steward whether the set covers exactly today's stewards. It guessed: consecutive `recovery.share` events from one sender, in consecutive log positions, with a changing target. The server numbers events as they arrive, so another member's event can land between two shares of one batch. The set then looked like two sets, the holders looked incomplete, and `sendMissingShares` could send a new set for nothing. This was listed as a known weakness.

## Decision

A `recovery.share` carries one more public field, `set`: 32 lowercase hex characters, random, the same on every share of a set. The fold groups shares of an epoch by it.

- The steward who sends the first share of a set owns the set name: a share with that name from another steward is refused ("that share set belongs to another steward").
- A share starting a different name starts a new set and replaces the holders of the old one (as before).
- `key.wrap` must not carry `set`; the server and the fold refuse it there.
- Shares with no `set` (made by earlier builds) still fold with the old heuristic, so nothing already in a log is refused.

## Consequences

- Interleaved events no longer split a set; the "stale set" resend does not fire for no reason.
- The set name is public and says nothing about the key or the shares.
- The server only validates shape (32 hex, recovery shares only); the grouping is client fold logic like the rest of ADR 0048.
- Tests: `RecoverySharesContract` (an unrelated event between shares keeps one set; the name's format; key wraps carry none).
