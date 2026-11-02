# 0024. Retire the plaintext `v = 0` format

- Status: Accepted
- Date: 2026-11-03

## Context

ADR 0014 introduced `v = 0`, a plaintext development format used before encryption existed, and said it must be removed before a real pilot. ADR 0021 added sealed `v = 1` and kept `v = 0` behind the operator setting `CIRCLES_ALLOW_PLAINTEXT_DEV` (default off) so the M1 tests could keep running. An operator flag that stores private payloads in the clear is a risk by itself, and every `v = 0` code path (payload parsing, note and label checks on the server, an unencrypted `EventAuthor` mode) is code that exists only to be removed.

## Decision

- `v = 1` is the only format. A server refuses every other `v` with `unsupported_version`. There is no setting that changes this.
- `EventAuthor` always seals and needs a `Cipher`. `DeviceIdentity` always has a real X25519 box key pair. The random-bytes box key of M1 is gone.
- The server no longer reads any secret part, so it no longer checks the length of notes and labels. Their size is bounded only by `MAX_ENCRYPTED_BYTES`; clients may enforce shorter limits when they build events.
- `CIRCLES_ALLOW_PLAINTEXT_DEV` is removed. A server started with it set ignores it, as with any unknown variable.
- M1 test data in `v = 0` is not migrated (ADR 0014 already said so).

## Consequences

- The server cannot be configured into storing readable payloads, and there is one format to reason about, test and document.
- Tests that only worked because a note or label was readable by the server (length checks on `member.invite` and `device.add`) are removed; a test that needs a secret part must first create a circle key.
- Amends ADR 0014 (payload format) and ADR 0021 point 7 (the `v = 0` flag); both stay accepted for the rest of their content.
