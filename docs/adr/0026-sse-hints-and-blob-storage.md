# 0026. Server-sent event hints and blob storage

- Status: Accepted
- Date: 2026-11-03

## Context

Two items are still open from the spec: whether the server serves SSE in v1 (open decision 5, spec 4.3 marks it optional) and how blobs (encrypted attachments such as item photos) are stored and limited (spec 4.2 and 4.3 give the endpoints and a 2 MiB cap only). The server is one process and the operator is not trusted, so neither feature may reveal more than ciphertext sizes and timing.

## Decision

**SSE (hints only, optional)**
1. The server serves `GET /api/v1/circles/{id}/events/stream` as `text/event-stream`. It carries no event data: each message is `data: {"serverSeq": N}`, the newest sequence number of the circle. A client that sees a number above its cursor pulls as usual. Clients must still work without it (polling).
2. The response is written by hand, after authentication, so a bad token gets an ordinary `401` problem response and not a half-open stream. Browsers' `EventSource` cannot send an `Authorization` header, so clients read the stream with `fetch` and the bearer token.
3. On connect the server sends `retry: 5000` and one message with the current newest number, so a client that missed pushes notices at once. It sends a `: keep-alive` comment every 25 seconds and re-checks the token each time; the stream ends when the session expires or the device is revoked.
4. Limits: at most 3 streams per device and 500 in the process (`429` and `503` problems), and a rate bucket of 6 opens per minute per device. Updates are conflated: a slow reader only ever receives the newest number.
5. The hub that fans out hints lives in memory, which fits the single-process deployment. It would need replacing before any multi-process deployment.

**Blobs**
6. `PUT /api/v1/circles/{id}/blobs/{hash}` stores up to 2 MiB; `GET` returns it. `{hash}` is the lowercase hex BLAKE2b-256 of the body, which the server recomputes: `422` on a mismatch, `413` over the cap, `400` for a malformed hash. A repeated `PUT` of the same blob is `200`; the first is `201`.
7. Bytes live on disk at `data/blobs/<circleId>/<aa>/<hash>`, written to a temporary file and renamed only after the hash checks out. This puts the circle id in the path, unlike the spec's `data/blobs/<aa>/<hash>`, so circles never share files and deleting one circle is trivial. The `blobs` table is recreated with the primary key `(circle_id, hash)` (migration V4; the table was unused and empty).
8. Any registered, non-revoked device of the circle may `PUT` and `GET`. There is no per-blob permission: whoever is in the circle and knows the hash can fetch the ciphertext, and protection is cryptographic.
9. Each circle has a storage quota (`CIRCLES_BLOB_QUOTA_MIB`, default 256); beyond it a `PUT` gets `507`. Uploads are rate limited to 30 per minute per device. `GET` answers with `Cache-Control: private, max-age=31536000, immutable` and the hash as `ETag`, since the content never changes.
10. Clients seal a blob with XChaCha20-Poly1305 under the circle key (or an item key), with associated data `"circles/blob/v1"` plus the circle id, and use the hash of the ciphertext as the blob id. The reference to a blob sits inside an encrypted event payload, so the server cannot tell which blobs are still used and does not delete any. Operators reclaim space by export and import (spec 4.5).

## Consequences

- Live updates arrive in a moment, and a server that is down or blocks streaming costs only latency.
- A device in a circle can fill the circle's quota with junk; the quota and rate limit bound the damage, and a steward can revoke the device.
- Orphaned blobs stay until an export and import. The operator sees blob sizes, upload times and which device uploaded, as with events.
- The stream needs a long-lived connection per active client; a reverse proxy must not buffer or time out `text/event-stream`.
