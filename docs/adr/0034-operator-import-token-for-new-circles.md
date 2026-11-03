# 0034. An operator-issued import token for new circles

- Status: Accepted
- Date: 2026-11-03

## Context

ADR 0033 lets a steward named in an export file import it over HTTP, and for a circle the host has never seen the only proof of stewardship comes from the file itself. So anyone can write a file in which their own device is the steward of a circle they made up and import it, which is the same as creating a circle there. ADR 0033 limited this only with `CIRCLES_ALLOW_CREATE=false`. The owner wants the host operator to decide which new circles may arrive this way.

## Decision

1. **A new circle needs a token.** An `POST /api/v1/admin/import` for a circle this host does not yet have must carry the header `X-Circles-Import-Token: <token>`. The token is issued by the host operator on the command line:
   `circles-server import-token <circleId> [--hours N]` prints a random token (32 bytes, base64url) once. N is from 1 to 168 hours and defaults to 24.
2. **What a token is.** It is bound to one circle id, expires, and works once. The server stores only its BLAKE2b-256 hash, the circle id and the expiry (migration V6); expired tokens are deleted with other expired rows.
3. **When it is checked.** After the signature, the whole-file verification and the check that the signing device is a steward in the file, and immediately before the first event is written. A request that fails any earlier check, or lacks a valid token, does not use the token up. A missing, wrong, expired, used or other-circle token is refused with `403` and the same message, without saying which.
4. **Closed hosts.** The token is the operator's explicit approval, so it works on a host with `CIRCLES_ALLOW_CREATE=false` too. That setting no longer matters for HTTP import: a new circle always needs a token, and no token is ever needed to extend a circle the host already has.
5. **Extending is unchanged.** A circle the host already has takes imports from its current stewards without a token (ADR 0033 point 5), including resuming an import that stopped part-way, because the circle exists after its first events.
6. **The command-line `import` is the operator's own** and needs no token (ADR 0030).
7. **The signature does not cover the token.** An attacker who can read the request (no TLS) could replay a captured token with a file of their own, but only for the one circle id it names, only once, and only until it expires. Hosts must be served over HTTPS; the operator guide will say so.

## Consequences

- A stranger can no longer create a circle on someone's host by importing a made-up file; the operator hands a token to the steward who is allowed to bring a circle over.
- Remote handover still works without shell access for the steward: the operator issues the token, the steward uses it once.
- An operator must run a command for each incoming circle; that is the intended friction.
- Tokens that leak while unused cost one circle id and a time window; they cannot be used for another circle.
- Amends ADR 0033 point 6 (the closed-host rule) and adds one table and one command.
