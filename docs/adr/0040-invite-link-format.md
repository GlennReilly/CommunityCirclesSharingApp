# 0040. Invite link format

- Status: Accepted
- Date: 2026-11-03

## Context

A `member.invite` event publishes an invite public key; the matching secret has to reach the invitee out of band, and the spec says it travels "in the link fragment" without fixing a format. Links get pasted into chats, so the format is hard to change once any have been sent. The browser never sends the part after `#` to a server, and the page is served with `Referrer-Policy: no-referrer` (ADR 0037).

## Decision

1. A link is `{origin}/#/join/{circle}/{invite}/{secret}`:
   - `circle`: the circle id, 32 lowercase hex characters;
   - `invite`: the id of the `member.invite` event, 64 lowercase hex characters;
   - `secret`: the invite key's Ed25519 seed, 32 bytes as base64url without padding (43 characters).
   The origin is the server the circle lives on (`CIRCLES_BASE_URL`, or the origin the inviter's browser was served from). The client always talks to its own origin, so the link must be opened on that server.
2. The parser is strict: exactly this shape, exact lengths and alphabets, nothing after the secret; anything else is "not an invite link". It accepts a full URL, or just the part from `#/join/`.
3. After reading the link the page removes the fragment from the address bar (`history.replaceState`), so the secret is not left in the browser history entry or a later screenshot of the address.
4. Default terms when a member makes an invite: one use, valid for 14 days (constants; the server already enforces both). A longer-lived or multi-use invite is a later steward option.
5. The secret is a bearer secret: whoever has the link can join until it is used or expires. The invite proof (`member.join` signed with the invite key) is what the server and every client check, so the link alone gives access to joining, never to reading: a new member still needs a steward to wrap the circle key to their device.

## Consequences

- Links are short enough for a messaging app and carry nothing the server can read.
- A link in a chat history is a live credential until used; one use and 14 days limit that.
- Changing the format later means supporting both shapes for as long as old links might exist.
- The circle name is not in the link (events carry none yet), so the join screen can only say "Join this circle" and the server address.
