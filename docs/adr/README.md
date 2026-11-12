# Architecture decision records

One short file per decision that is hard to reverse, changes a rule, picks a technology, or answers a question the project says to ask first. Copy [template.md](template.md), number it `NNNN-slug.md`, and add it below.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-kotlin-multiplatform-and-ktor.md) | Kotlin Multiplatform shared module, Ktor server, SQLite | Accepted |
| [0003](0003-append-only-signed-event-log.md) | Append-only signed event log | Accepted |
| [0004](0004-public-and-encrypted-event-parts.md) | Events have a `public` and an `encrypted` part | Accepted |
| [0005](0005-tiers-are-advisory.md) | Tiers are advisory; owners decide | Accepted |
| [0006](0006-optimistic-returns.md) | Optimistic returns with owner dispute | Accepted |
| [0007](0007-integer-days-and-fixed-point-points.md) | Integer days and rounded points in policy code | Accepted |
| [0008](0008-incident-cooldown.md) | Incident cooldown instead of negative standing | Accepted |
| [0009](0009-web-ui-stack.md) | Web UI stack | Accepted (Kotlin only) |
| [0010](0010-jvm-crypto-jdk-and-bouncycastle.md) | JVM crypto for M1: JDK Ed25519 and BouncyCastle BLAKE2b | Accepted |
| [0011](0011-drop-unique-device-counter.md) | Drop UNIQUE(device, ctr) on events | Accepted |
| [0012](0012-canonical-encoding-and-event-identity.md) | Canonical encoding, event identity and wire format | Accepted |
| [0013](0013-identifiers-and-self-registering-events.md) | Identifiers and self-registering events | Accepted |
| [0014](0014-m1-server-rules-and-dev-payload-format.md) | M1 server rules and the plaintext development payload format | Accepted (rate limiting part superseded by 0015, payload format by 0024) |
| [0015](0015-rate-limiting-pulled-forward.md) | Rate limiting pulled forward from M5 | Accepted |
| [0016](0016-structured-json-logging.md) | Structured JSON logging without a logging framework | Accepted |
| [0017](0017-shadow-jar-for-single-file-deploy.md) | One runnable jar with the Shadow plugin | Accepted |
| [0018](0018-m2-libsodium-bindings.md) | M2 libsodium bindings: lazysodium (test scope) on JVM, libsodium-wrappers on JS | Accepted (the JS part replaced by 0035) |
| [0019](0019-cipher-separate-from-crypto.md) | Encryption lives in a separate `Cipher` interface (amends 0018 point 1) | Accepted |
| [0020](0020-shamir-own-implementation.md) | Shamir secret sharing: own implementation with independent vectors | Accepted |
| [0021](0021-sealed-envelope-v1-and-circle-key-wrapping.md) | Sealed envelope v = 1, circle keys and key wrapping | Accepted (v = 0 flag removed by 0024) |
| [0022](0022-steward-changes-without-a-quorum.md) | Steward changes: any steward acts, the last steward stays | Superseded by 0023 |
| [0023](0023-steward-changes-need-a-quorum.md) | Steward changes need a quorum of distinct stewards | Superseded by 0027 |
| [0024](0024-retire-the-plaintext-v0-format.md) | Retire the plaintext v = 0 format (amends 0014, 0021) | Accepted |
| [0025](0025-item-and-loan-event-payloads.md) | Item and loan event payloads and folding rules | Accepted |
| [0026](0026-sse-hints-and-blob-storage.md) | Server-sent event hints and blob storage | Accepted |
| [0027](0027-steward-quorum-setting-and-inactivity-fallback.md) | Steward governance settings and the inactivity fallback (supersedes 0023) | Accepted (minimum silent days changed by 0029) |
| [0028](0028-client-image-policy.md) | Client image policy: resize, re-encode, strip metadata | Accepted |
| [0029](0029-lower-the-minimum-silent-days.md) | Lower the minimum silent days of the steward fallback to 7 (amends 0027) | Accepted |
| [0030](0030-circle-export-import-and-verify.md) | Circle export, import and verify (`circles-export/1`) | Accepted |
| [0031](0031-server-backup-and-restore.md) | Server backup and restore (`circles-backup/1`) | Accepted (points 6, 7, 9 amended by 0032) |
| [0032](0032-explicit-backup-folder-and-full-pre-migration-backup.md) | An explicit backup folder, and a full backup before every migration (amends 0031) | Accepted |
| [0033](0033-signed-steward-admin-requests.md) | Signed steward requests for export and import over HTTP | Accepted (point 6 amended by 0034) |
| [0034](0034-operator-import-token-for-new-circles.md) | An operator-issued import token for new circles (amends 0033) | Accepted |
| [0035](0035-browser-crypto-with-noble.md) | Browser cryptography with the noble libraries (amends 0018) | Accepted |
| [0036](0036-browser-local-storage-with-indexeddb.md) | Browser local storage with IndexedDB, and verify once | Accepted |
| [0037](0037-serve-the-web-client-from-the-server.md) | Serve the web client from the server, same origin | Accepted |
| [0038](0038-client-sync-engine.md) | Client sync engine: shared code, verify once at ingest | Accepted |
| [0039](0039-device-key-storage-in-the-browser.md) | Device key storage in the browser | Accepted |
| [0040](0040-invite-link-format.md) | Invite link format | Accepted |
| [0041](0041-service-worker-and-updates.md) | Service worker: offline shell, no API caching, the member chooses when to update | Accepted |
| [0042](0042-standing-vouches-and-the-access-check.md) | Standing from loans and vouches, and the access check (M4, first slice) | Accepted |
| [0043](0043-confirmation-disputes-and-resolution.md) | Owner confirmation, disputes and their resolution (M4, second slice) | Accepted |
| [0044](0044-removing-members-and-rotating-the-circle-key.md) | Removing a member and rotating the circle key | Accepted |
| [0045](0045-circle-key-commitments.md) | Circle key commitments | Accepted |
| [0046](0046-adding-another-device.md) | Adding another device | Accepted (where the fingerprint comparison happens superseded by 0071) |
| [0047](0047-recovery-code.md) | The recovery code | Accepted |
| [0048](0048-recovery-shares-in-the-app.md) | Recovery shares in the app | Accepted |
| [0049](0049-item-keys-after-a-removal.md) | Item detail keys after a removal | Accepted |
| [0050](0050-pauses.md) | Pauses | Accepted |
| [0051](0051-config-events.md) | Config events | Accepted |
| [0052](0052-loan-windows.md) | Loan windows: no double booking, and cancelling | Accepted |
| [0053](0053-contribution-notes.md) | Contribution notes | Accepted |
| [0054](0054-hand-written-wire-json.md) | Hand-written wire JSON for the browser | Accepted |
| [0055](0055-skills.md) | Skill offers | Accepted |
| [0056](0056-passphrase-show-hide-and-saving.md) | Showing the passphrase, and saving it | Accepted |
| [0057](0057-visual-design-conventions.md) | Visual design conventions | Accepted |
| [0058](0058-server-compression.md) | The server compresses text itself | Accepted |
| [0059](0059-return-reminders.md) | Return reminders | Accepted |
| [0060](0060-item-photos.md) | Item photos | Accepted |
| [0061](0061-approving-other-dates.md) | Approving other dates | Accepted |
| [0062](0062-qr-codes.md) | QR codes | Accepted (fingerprint text on Members superseded by 0071) |
| [0063](0063-passkey-unlock.md) | Unlocking with a passkey | Accepted |
| [0064](0064-bundle-budget-300-kib.md) | Script budget raised to 300 KiB | Accepted |
| [0065](0065-recovery-share-set-id.md) | A recovery share names its set | Accepted |
| [0066](0066-rename-to-lendger.md) | The product is called Lendger | Accepted |
| [0067](0067-tier-names-new-regular-trusted.md) | Tier names are New, Regular and Trusted | Accepted (display wording superseded by 0070; identifiers stay) |
| [0068](0068-verified-display-names.md) | Display names: chosen by the member, confirmed by another | Accepted (point on shared names superseded by 0069; wording and where the confirm form and fingerprint sit superseded by 0071) |
| [0069](0069-unique-display-names.md) | Display names are unique within a circle | Accepted |
| [0070](0070-level-numbers.md) | People see level numbers, not tier names | Accepted |
| [0071](0071-checking-identity-in-person.md) | Checking identity in person: one check page, fingerprints only as the fallback | Accepted |
| [0072](0072-loan-history.md) | Loan history for owners and borrowers, with "Borrow again" | Accepted |

## Open decisions still to record

From the spec, section 11. Record each as an ADR when it is decided:

1. ~~libsodium binding for the JVM in M2~~: decided (ADR 0018). Shamir: decided (ADR 0020).
2. ~~Local replica store in the browser~~: decided, IndexedDB (ADR 0036).
3. ~~Kotlin/JS bundle size outcome and the fallback plan~~: measured and decided (ADR 0009, 0035): our code is about 77 KB gzipped; the browser uses noble (22 KB) instead of libsodium (245 KB).
4. ~~Recovery code wordlist and format~~: decided (ADR 0047).
5. ~~Whether the server serves SSE in v1 or polling only.~~ Decided: hint-only SSE, optional for clients (ADR 0026).
6. Default thresholds and half-life, after the pilot.
7. Whether dispute resolution needs more than one steward (default: one non-party steward).
8. ~~Whether to keep `UNIQUE(device, ctr)`~~: decided, dropped (ADR 0011).
