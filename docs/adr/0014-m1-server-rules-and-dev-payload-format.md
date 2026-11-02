# 0014. M1 server rules and the plaintext development payload format

- Status: Accepted (the "No rate limiting in M1" decision is superseded by ADR 0015; the payload format is retired by ADR 0024)
- Date: 2026-11-02

## Context

M1 delivers signed events and sync before encryption (M2). The spec has no endpoint for `circle.create` (the founder has no session yet), and optional notes and labels (`member.invite`, `device.add`) are secret in the final design.

## Decision

- **Payload format.** Envelope `v = 0` is the plaintext development format: `encrypted` holds the canonical CBOR of the payload map and `nonce` is empty. `v = 1` will be the sealed format in M2. M1 servers accept only `v = 0`; M2 servers will refuse `v = 0` unless an operator flag allows it. M1 data is therefore a development format and is not migrated.
- **Event types.** An allow-list of `circle.create`, `member.invite`, `member.join` and `device.add`. Other known types are rejected as `unsupported_type`.
- **Public endpoints.** `POST /api/v1/circles` takes one `circle.create` envelope; `POST /api/v1/circles/{id}/join` takes one `member.join` envelope (spec 4.3). Neither needs a session, because the sender has no registered device yet. Creation is open by default and can be switched off with `CIRCLES_ALLOW_CREATE=false`. Ordinary push and pull need a session token for a non-revoked device of that circle; any such device may relay any valid event.
- **No rate limiting in M1.** The limits in spec 4.3 are deferred to M5 (operations). Until then a public server should sit behind a reverse proxy that limits requests, or have creation switched off.
- **Extra tables.** `V1__init.sql` follows spec 4.2, plus `v` on `events`, a non-unique index on `(device, ctr)` (ADR 0011), and the tables `stewards` and `invites` that the server needs to enforce roles and invite limits.
- **Migrations.** `migrate` applies pending migrations; `serve` migrates a brand-new database itself and refuses to start on an older or newer one.

## Consequences

Anyone can create a circle on an open server and spam it until M5 adds limits. A joined device can pull the (plaintext, in M1) log immediately; from M2, reading is gated by key wrapping and the security note must say that the server grants log access to any registered device. The `v = 0` format must be removed before a real pilot.
