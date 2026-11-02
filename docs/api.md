# API reference (M1, M2 changes below)

JSON over HTTP, under `/api/v1` unless noted. Types are in `shared/src/commonMain/kotlin/circles/api/Api.kt`. Errors are RFC 9457 `application/problem+json` with no stack traces. Event rules and the wire format of an envelope are in the [spec](spec/community-sharing-app-spec.md) (section 3) and [ADR 0012](adr/0012-canonical-encoding-and-event-identity.md). In M1 every event had `v = 0` (plaintext development format, [ADR 0014](adr/0014-m1-server-rules-and-dev-payload-format.md)). From M2 the format is `v = 1` ([ADR 0021](adr/0021-sealed-envelope-v1-and-circle-key-wrapping.md)): `nonce` is 24 bytes and `encrypted` is XChaCha20-Poly1305 ciphertext whenever the event has a secret part, and `epoch` names the circle key used. Every other `v` (including the old plaintext `v = 0`) is refused with `unsupported_version` ([ADR 0024](adr/0024-retire-the-plaintext-v0-format.md)). New accepted types: `key.wrap` (public `{device, epoch, wrapped}`, sealed key for a device of the circle; the first one must come from a steward and be epoch 1) and `key.rotate` (steward only, public `{epoch}` = current + 1). New rejection use: `malformed` for an epoch the circle has not reached.

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

[ADR 0015](adr/0015-rate-limiting-pulled-forward.md). Token buckets, per minute: auth 10 per client address (`/auth/challenge` and `/auth/session` share it, so a login uses two), push 60 per device (per request), join 5 and create 5 per client address. Pull and the probes are not limited. A refused request gets `429` problem+json and `Retry-After` in seconds. `CIRCLES_RATE_LIMIT=false` turns it off.

## Rejection reasons

`malformed`, `unsupported_version`, `unsupported_type`, `too_large`, `future_day`, `unknown_circle`, `circle_exists`, `creation_disabled`, `wrong_circle`, `unknown_device`, `device_revoked`, `device_exists`, `member_exists`, `author_mismatch`, `bad_signature`, `not_steward`, `invalid_public`, `invalid_payload`, `unknown_invite`, `invite_expired`, `invite_exhausted`, `bad_invite_proof`.

## Not yet

SSE stream, blobs, admin import/export, and any event type other than `circle.create`, `member.invite`, `member.join` and `device.add`.
