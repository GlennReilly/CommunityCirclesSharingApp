# 0004. Events have a `public` and an `encrypted` part

- Status: Accepted
- Date: 2026-11-02

## Context

The server must validate events (device keys, steward-only types) but must not read item, loan, vouch or standing details. An all-or-nothing encrypted payload forced contradictions: the server could not know who the stewards or devices were.

## Decision

Each event carries two CBOR maps. `public` is readable by everyone, signed but not encrypted. `encrypted` is sealed with the circle key, and its associated data includes `public`, so the two cannot be separated. A field goes in `public` only if the server enforces a rule with it, or it must be readable before the reader holds the circle key. Everything else goes in `encrypted`.

## Consequences

The server derives devices and the steward set from `public`. Vouch targets stay hidden, so the server cannot see who vouches for whom. The operator can see the steward set and which device is wrapped to which epoch; the security note must say so. Server-side checks are spam hygiene, not security, because the operator is not trusted; clients re-validate every event.
