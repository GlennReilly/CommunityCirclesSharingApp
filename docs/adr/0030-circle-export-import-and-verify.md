# 0030. Circle export, import and verify (`circles-export/1`)

- Status: Accepted
- Date: 2026-11-03

## Context

Spec 4.5 makes handover a core feature: a circle must survive its host. A steward, or anyone holding a replica, must be able to seed a new host from nothing but ciphertext and public keys. The spec names the format `circles-export/1` (newline-delimited JSON envelopes plus a manifest) but leaves the container, the checks, and what an import may and may not do open. Backup and restore of a whole server, the signed administration endpoints, scheduling and packaging are separate pieces and get their own decisions.

## Decision

1. **Container.** An export is one ZIP file (`java.util.zip` on the server, any zip library in a client) with these entries and nothing else:
   - `manifest.json`;
   - `events.ndjson`: one `EnvelopeDto` JSON object per line, in `serverSeq` order, the same wire form as the API;
   - `blobs/<hash>`: one encrypted blob per file, named by its BLAKE2b-256 hash.
2. **Manifest.** `{format: "circles-export/1", circle, eventCount, lastSeq, eventsHash, blobs: [{hash, size}]}`. `eventsHash` is the lowercase hex BLAKE2b-256 of the exact bytes of `events.ndjson`. Nothing in the manifest is secret; it contains no key, name or timestamp of the export.
3. **Content.** Only what any member device can already pull: ciphertext, public parts, signatures and public keys. Sessions, challenges, rate-limit state, the steward vote table and device rows are not exported; the importer rebuilds them by replaying the log.
4. **Export** is read-only. A client exports the log it holds in the same format, so a circle can be rebuilt from one member's replica; a client's `lastSeq` is the highest `serverSeq` it holds, and it exports no blobs it has not downloaded.
5. **Verify** checks, without touching any database, that: the manifest parses and names the supported format; `events.ndjson` matches `eventsHash` and has `eventCount` lines; every line is a valid envelope whose id recomputes and whose circle matches; sequence numbers run `1..lastSeq` without gaps if the file claims them; every blob file exists, matches its hash and size, and is within the size limit, and there are no extra entries; and a structural fold of the log (signatures, device registration and revocation, roles, quorum, invites, epochs) accepts every event. It reports every problem, not just the first.
6. **Import** replays the events, in file order, through the same server rules that accept pushed events, as one pass with no session and no rate limit. Therefore signatures, roles, invites and steward rules are all re-checked and `devices`, `stewards`, epochs and sequence numbers are rebuilt. An import is accepted even if `CIRCLES_ALLOW_CREATE` is off, because it is the operator seeding their own host.
7. **Same sequence numbers.** Replaying an unbroken log into an empty circle gives each event the `serverSeq` it had, so clients' cursors and existing devices keep working: they log in again with the signing key their device already has (no re-enrolment) and pull from where they stopped.
8. **Idempotent and safe.** Importing the same file again reports every event as a duplicate and changes nothing. If the circle already exists with different history (the first event differs, or an event is refused because it conflicts), the import stops and reports it; it never rewrites or deletes existing events. A refused event aborts the import of that circle, and everything accepted before it stays (partial imports are visible in the report and can be resumed with the same file).
9. **Blobs** are imported after the events, each through the normal hash-checked, quota-checked blob path.
10. **Limits against hostile files.** Import and verify read at most 1,000,000 events and 200,000 blobs per file, refuse entries whose declared size is over the blob cap (2 MiB) or whose total uncompressed size is over 64 GiB, refuse names that are not `manifest.json`, `events.ndjson` or `blobs/<64 hex>`, and read an event line only up to its size limit.
11. **Command line.** `circles-server export <circleId> <file>`, `import <file>` and `verify <file>`, in the same shared code path the tests use. Authorised HTTP administration, `backup` and `restore` are decided separately.

## Consequences

- A circle can move host with nothing but a file, and any member's replica can reconstruct it; both handover tests of the spec can be run as automated tests.
- The importer trusts nothing in the file: a forged, truncated or reordered export is refused by the same rules that protect the live server.
- The format is a contract: changing a field or a rule needs `circles-export/2` and a new ADR; the importer must keep reading `/1`.
- Because export is the only way to shed unreferenced blobs (ADR 0026), the importer only brings along blobs listed in the manifest, which the exporter lists in full; a smarter exporter that lists only referenced blobs is possible later without changing the format.
- A zip bomb or a million tiny events costs the operator time and disk, not safety; the limits bound it.
