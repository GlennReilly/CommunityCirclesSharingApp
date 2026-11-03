# 0033. Signed steward requests for export and import over HTTP

- Status: Accepted
- Date: 2026-11-03

## Context

Spec 4.3 and 4.5 give `GET /admin/export/{circleId}` and `POST /admin/import`, "authorised by a signed steward request, not by a server password", so that a steward can hand a circle to a new host without shell access. The spec says nothing about the signed message, replay, or how a brand-new host, which has never heard of the circle, can know the caller is a steward. The file format and its checks are ADR 0030; this ADR is only about who may call these two endpoints and how.

## Decision

1. **Endpoints.** `GET /api/v1/admin/export/{circleId}` answers with the export zip. `POST /api/v1/admin/import` takes the zip as the request body and answers `200` with `{eventsAccepted, eventsAlready, blobsStored, blobsAlready}`. There is no session and no server password.
2. **The signed request.** Every call carries `Authorization: Circles-Signature device=<deviceId>, ts=<unix seconds>, nonce=<base64url, 16 bytes>, sig=<base64url>`. `sig` is the Ed25519 signature, by that device's signing key, over the bytes `"circles/admin/v1"`, then a newline-separated `METHOD`, request path (no query), `ts`, `nonce` and the lowercase hex BLAKE2b-256 of the body (of the empty body for `GET`). The message covers the method, path and body, so a captured header cannot be used for anything else.
3. **Freshness and replay.** `ts` must be within 5 minutes of the server's clock, and a `nonce` is accepted once within 10 minutes. The nonce memory lives in the process; a restart forgets it, which leaves a replay window of at most the remaining skew, and replaying an export or an import is harmless (an export only returns what members can already pull; a repeated import changes nothing, ADR 0030).
4. **Who may export.** The signing device is registered on this host, not revoked, and its member is a current steward of the circle named in the path.
5. **Who may import.** The host may not know the circle, so the proof comes from the file. The server finds the device's registration in the file's own `circle.create`, `member.join` and `device.add` events and checks the signature against that key. Only after the signature holds does it verify the whole file (ADR 0030); then the signing device must be registered and not revoked in the file's final state, and its member must be a steward in it. If the circle already exists on this host, the device must also be a current steward here.
6. **Closed hosts.** An import that would create a circle this host does not have is refused with `403` when `CIRCLES_ALLOW_CREATE=false`, so a steward cannot get around a closed host. (The command-line `import` is the operator's own and ignores that setting, as in ADR 0030.) Extending a circle the host already has is allowed.
7. **Limits.** An import body is read into memory and capped by `CIRCLES_ADMIN_IMPORT_MAX_MIB` (default 64); larger circles use the command line. There is one import at a time (`429` when busy). Both endpoints share a rate bucket of 6 calls per minute per client address.
8. **Answers.** A missing, malformed, stale, replayed or wrongly signed request gets one `401` with no detail about which check failed. A valid signature from a device that is not allowed gets `403`; an unknown circle on export gets `404`, a body over the cap `413`, a file that does not verify `422` with the problems.
9. **Logging.** Counts and outcomes only, never device ids, circle ids, nonces or signatures (ADR 0016).
10. **Clients.** The message, header format and signing helper live in `shared` (JVM and JS), so the web client signs requests with the same code the tests use.

## Consequences

- A steward can move a circle to a new host, or pull its export, from any client holding a device key, with nothing but that key and the file.
- The new host trusts nothing it has not checked: the caller proves control of a device key that the file itself says belongs to a steward, and the file is fully verified before a single event is written.
- An unauthenticated caller can make the server read and hash a body of up to the cap and parse its event lines before the signature is checked; the cap, the rate limit and the single-import lock bound that cost.
- Replays inside a restart window are possible but harmless.
- Importing very large circles over HTTP is not supported; that is a deliberate memory bound, not a protocol limit.
