# API reference (M1, M2 changes below)

JSON over HTTP, under `/api/v1` unless noted. Types are in `shared/src/commonMain/kotlin/circles/api/Api.kt`. Errors are RFC 9457 `application/problem+json` with no stack traces. Event rules and the wire format of an envelope are in the [spec](spec/community-sharing-app-spec.md) (section 3) and [ADR 0012](adr/0012-canonical-encoding-and-event-identity.md). In M1 every event had `v = 0` (plaintext development format, [ADR 0014](adr/0014-m1-server-rules-and-dev-payload-format.md)). From M2 the format is `v = 1` ([ADR 0021](adr/0021-sealed-envelope-v1-and-circle-key-wrapping.md)): `nonce` is 24 bytes and `encrypted` is XChaCha20-Poly1305 ciphertext whenever the event has a secret part, and `epoch` names the circle key used. Every other `v` (including the old plaintext `v = 0`) is refused with `unsupported_version` ([ADR 0024](adr/0024-retire-the-plaintext-v0-format.md)). New accepted types: `key.wrap` (public `{device, epoch, wrapped}`, sealed key for a device of the circle; the first one must come from a steward and be epoch 1) and `key.rotate` (steward only, public `{epoch}` = current + 1). Also accepted from any registered device of the circle: `item.upsert`, `item.retire`, `item.detail`, `loan.request`, `loan.respond`, `loan.handover`, `loan.return` ([ADR 0025](adr/0025-item-and-loan-event-payloads.md)). They must have an empty `public` and a sealed payload; the server cannot read or judge the fields, so clients enforce ownership and loan rules while folding. New rejection use: `malformed` for an epoch the circle has not reached.

An envelope is `{v, circle, author, device, type, day, ctr, epoch, public, nonce, encrypted, sig}`. Ids are 32 lowercase hex characters; `public`, `nonce`, `encrypted` and `sig` are base64url without padding, and `public` is the canonical CBOR of the public map.

## Unauthenticated

| Call | Body | Answer |
|---|---|---|
| `GET /healthz`, `GET /readyz` (root, not under `/api/v1`) | | `ok`, `ready` (`503` problem if the database is unusable) |
| `GET /version` (root) | | `{name, version}` |
| `POST /circles` | `{event}` a `circle.create` | `201`, `200` (duplicate) or `422` with a `PushResult`; `403` if creation is switched off |
| `POST /circles/{id}/join` | `{event}` a `member.join` | same |
| `POST /auth/challenge` | `{deviceId}` | `{nonce}` (base64url, valid 60 s; always answered, even for unknown devices) |
| `POST /auth/session` | `{deviceId, nonce, sig}` where `sig` signs `"circles/auth/v1" ‖ nonce ‖ deviceId` | `{token, expiresIn}` (12 h) or `401` |

## Authenticated (`Authorization: Bearer <token>`)

| Call | Answer |
|---|---|
| `POST /circles/{id}/events` body `{events: [Envelope]}`, at most 200 | `200` with one `PushResult` per event: `{id, status: accepted\|duplicate\|rejected, serverSeq?, reason?, detail?}` |
| `GET /circles/{id}/events?after=0&limit=500` | `{events: [{serverSeq, envelope}], next, hasMore}`; `limit` is 1 to 500 |

Status codes: `401` missing or bad token, `403` the device is not in that circle, `404` malformed circle id, `400` malformed body, `413` body over 16 MiB, `429` rate limit.

## Rate limits

[ADR 0015](adr/0015-rate-limiting-pulled-forward.md). Token buckets, per minute: auth 10 per client address (`/auth/challenge` and `/auth/session` share it, so a login uses two), push 60 per device (per request), join 5 and create 5 per client address. Pull and the probes are not limited. A refused request gets `429` problem+json and `Retry-After` in seconds. `LENDGER_RATE_LIMIT=false` turns it off.

## Sealed event types

Accepted from any registered, unrevoked device of the circle, with an empty `public` and a sealed payload the server cannot read (it checks only the sender, the epoch and that a payload exists): `item.upsert`, `item.retire`, `item.detail`, `loan.request`, `loan.respond`, `loan.handover`, `loan.return`, `loan.cancel`, `loan.dispute`, `loan.resolve`, `vouch`, `standing.note`, `standing.confirm`, `pause.propose`, `pause.review`, `pause.appeal`, `pause.lift`, `config.update`, `skill.upsert`, `skill.retire`, and since ADR 0068 `member.profile` (a member's display name) and `member.name.confirm` (another member confirms it; shown to people as confirming their identity, ADR 0071). Clients apply the rules while folding. Every type declared in the spec is now accepted; anything else is refused with `unsupported_type`.

## Rejection reasons

`malformed`, `unsupported_version`, `unsupported_type`, `too_large`, `future_day`, `unknown_circle`, `circle_exists`, `creation_disabled`, `wrong_circle`, `unknown_device`, `device_revoked`, `device_exists`, `member_exists`, `author_mismatch`, `bad_signature`, `not_steward`, `invalid_public`, `invalid_payload`, `unknown_invite`, `invite_expired`, `invite_exhausted`, `bad_invite_proof`, `steward_protected` (a steward's device may only be revoked by that steward, ADR 0044).

## Not yet

SSE stream, blobs, admin import/export, and any event type other than `circle.create`, `member.invite`, `member.join` and `device.add`.

## Streams and blobs (ADR 0026)

| Call | Body | Answer |
|---|---|---|
| `GET /circles/{id}/events/stream` (session) | | `text/event-stream`: `retry: 5000`, then `data: {"serverSeq":N}` with the circle's newest number on connect and again for each newer event; `: keep-alive` every 25 s. Hints only: pull to get events. `401`, `403`, `404` as for pull; `429` over 3 streams per device; `503` if the server is full |
| `PUT /circles/{id}/blobs/{hash}` (session) | ciphertext, at most 2 MiB | `201` stored, `200` already stored, `400` bad hash, `413` too big, `422` hash is not the BLAKE2b-256 of the body, `507` circle quota full |
| `GET /circles/{id}/blobs/{hash}` (session) | | the bytes (`application/octet-stream`, `ETag` is the hash, `Cache-Control: private, immutable`), or `404` |

`{hash}` is 64 lowercase hex characters, the BLAKE2b-256 of the (encrypted) body. Browsers' `EventSource` cannot send the `Authorization` header, so web clients read the stream with `fetch`. A reverse proxy must not buffer or time out `text/event-stream`; the server sends `X-Accel-Buffering: no`. Rate limits per device and minute: 6 stream opens, 30 blob uploads.

## Steward governance (ADR 0027)

`circle.create` may carry three optional public keys, `stewardQuorum` (1 to 9, default 2), `stewardInactiveDays` (7 to 365, default 60) and `stewardFallbackDelayDays` (7 to 90, default 14) and a fourth, `stewardRecoveryThreshold` (0 for no recovery shares, the default and what an absent key means, or 2 to 9; ADR 0048); values outside the limits are refused with `invalid_public`. `steward.policy` (steward only, `public = {quorum, inactiveDays, fallbackDelayDays}` plus an optional `recoveryThreshold` that is written only when it is not 0) changes them once the current quorum of distinct stewards have each sent the same values. `steward.add` and `steward.remove` take effect when the quorum agrees, at once when the quorum is 1, or by the inactivity fallback: the proposer repeats the event after the delay, the other stewards having been silent for the quiet period before the first one and silent since. Votes that have not taken effect are accepted and recorded, so a first `steward.add` or `steward.remove` is answered `accepted` even though nothing changes yet.

## Signed steward export and import (ADR 0033)

| Call | Body | Answer |
|---|---|---|
| `GET /admin/export/{circleId}` | | The `circles-export/1` zip, if the signing device is a current steward of that circle on this host |
| `POST /admin/import` | an export zip, at most `LENDGER_ADMIN_IMPORT_MAX_MIB` | `{eventsAccepted, eventsAlready, blobsStored, blobsAlready}`, if the signing device is a steward in the file (and, for a circle this host already has, a steward here) |

No session and no server password. A circle this host does not have yet also needs the operator's single-use token in the header `X-Circles-Import-Token` ([ADR 0034](adr/0034-operator-import-token-for-new-circles.md)): the host operator runs `lendger-server import-token <circleId> [--hours N]` and gives the printed token to the steward. Every call carries `Authorization: Circles-Signature device=<id>, ts=<unix seconds>, nonce=<base64url 16 bytes>, sig=<base64url>`. `sig` is the device's Ed25519 signature over `circles/admin/v1`, then `METHOD`, the request path, `ts`, `nonce` and the hex BLAKE2b-256 of the body, each on its own line. `ts` must be within 5 minutes of the server's clock and a nonce works once. Any missing, malformed, stale, replayed or wrongly signed request is a bare `401`; a valid signature from a device that may not do it is `403` (including a new circle without a valid token, whether or not the host has closed circle creation); `413` over the size cap; `422` with the problems if the file does not verify; `429` when another import is running or the rate limit (6 per minute per client address) is hit. `AdminAuth` in `shared` builds and checks the header for clients.

