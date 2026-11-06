# 0066. The product is called Lendger

- Status: Accepted
- Date: 2026-11-06

## Context

The working title was "Circles". The owner chose "Lendger". "Circle" is also the name of the concept (a group of members), and the code uses `circles.*` packages and signed or hashed strings that must not change without breaking existing logs.

## Decision

Rename the product, not the concept, and not anything that is signed, hashed or stored in a browser.

Changed: all text shown to people (page titles, headings, passkey name), documentation, README, `LENDGER_*` environment variables (were `CIRCLES_*`), `lendger-server.jar` and CLI name, `lendger.db`, `lendger-backup-<time>.zip` file names, Docker image, compose service and volume (`lendger-data`), systemd unit, paths and user in the operator guides.

Kept: Kotlin packages `circles.*`, the word "circle" for a group, `circles-export/1` and `circles-backup/1` format ids, the `X-Circles-Import-Token` header, the `Circles-Signature` scheme, `circles/key-commitment/v1` and other hashed strings, the IndexedDB name and `circles.textSize`/`circles.theme` keys, `circles.js`, and accepted ADRs (they keep the old name as history).

## Consequences

- Existing deployments must rename `CIRCLES_*` settings, `circles.db` (and `-wal`, `-shm`) and the Docker volume, or start empty.
- A later ADR may rename packages and wire strings together with a format change.
