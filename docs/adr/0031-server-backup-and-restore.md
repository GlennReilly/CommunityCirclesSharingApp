# 0031. Server backup and restore (`circles-backup/1`)

- Status: Accepted (points 6, 7 and 9 amended by ADR 0032)
- Date: 2026-11-03

## Context

Spec 4.5 asks for `backup <dir>` and `restore <file>`: a single archive of the database and blobs with a manifest and checksums, a restore that refuses to overwrite a non-empty data directory unless forced, an automatic backup before any migration, and optional scheduled backups (`CIRCLES_BACKUP_CRON`). Handover acceptance test 1 needs a host to be destroyed and rebuilt from a backup with existing devices reconnecting. Circle export and import (ADR 0030) move one circle by replaying its log; a backup is the whole server, byte for byte, and must not depend on replaying anything.

## Decision

1. **Archive.** One ZIP, `circles-backup/1`, with `manifest.json`, `circles.db` and `blobs/<circleId>/<aa>/<hash>`. The manifest is `{format, schemaVersion, includesBlobs, dbHash, dbSize, blobs: [{circle, hash, size}]}`: BLAKE2b-256 for the database file and every blob, no timestamps and nothing secret.
2. **Snapshot.** The database is copied with SQLite's `VACUUM INTO` over a second read connection, which gives a consistent snapshot while the server keeps writing (the database is in WAL mode). The spec names the online backup API; `VACUUM INTO` has the same effect, is a plain SQL statement, and also compacts the copy. The copy then has its `sessions` and `challenges` rows deleted, because they are useless after a restore and a stolen archive should not hold them.
3. **Blobs.** The snapshot is taken first, then exactly the blobs its `blobs` table lists are copied and their hashes re-checked. A listed blob whose file is missing or wrong fails the backup; there is no silent partial backup. Files that were uploaded after the snapshot are simply not in it.
4. **Verify.** `circles-server verify <file>` reads the manifest and handles both formats: for a backup it checks the manifest, every hash, that no other entries exist, that the database opens, passes `PRAGMA integrity_check`, and has the manifest's schema version, and that this server is not older than the archive. All problems are reported.
5. **Restore** (`restore <file> [--force]`, with the server stopped): verifies first, refuses an archive from a newer schema, and refuses a data directory that already holds `circles.db` or a non-empty `blobs/` unless `--force`. With `--force` nothing is ever deleted: the existing database (with its `-wal` and `-shm` files) and blobs are renamed to `*.before-restore-<yyyyMMddHHmmss>` and the operator removes them when satisfied. The database and blobs are written to temporary names and renamed into place last, so a failure before that leaves the old data as it was. An archive from an older schema restores as it is; running `migrate` afterwards brings it up to date (and takes its own backup, point 6).
6. **Before a migration.** `migrate` on an existing database with pending migrations first writes a database-only backup (`includesBlobs: false`, no blob copies) to `<dataDir>/backups/pre-migrate-from-v<N>-<time>.zip`. If that backup fails, the migration is not run. Restoring a database-only archive replaces only the database and keeps the existing blobs. A brand-new database has nothing to back up.
7. **Scheduling.** Instead of cron, which would need a parser nobody can audit, two settings: `CIRCLES_BACKUP_INTERVAL_HOURS` (whole hours, default off) and `CIRCLES_BACKUP_KEEP` (default 7). While `serve` runs, a background thread writes a full backup to `<dataDir>/backups/scheduled-<time>.zip` after each interval and deletes the oldest `scheduled-*.zip` files beyond the keep count; other files in that folder are never touched. A failed run is logged (no paths or data) and tried again next interval. This replaces `CIRCLES_BACKUP_CRON` of the spec.
8. **Command line.** `circles-server backup <dir>` (writes `circles-backup-<time>.zip` there, never overwrites), `restore <file> [--force]`, and the existing `verify`.
9. **Where backups live.** The default folder is inside the data directory, so it survives only as long as the disk does; operators must copy archives off the host. An archive holds ciphertext, public keys and metadata the operator can already see, and should still be kept private.

## Consequences

- A destroyed host is rebuilt from one file, and existing devices log in again with the keys they already have (handover test 1 runs as an automated test).
- A failed backup or restore never leaves a half-written file or loses the old data; the price is that a forced restore leaves renamed copies to clean up by hand.
- Cron expressions are not supported, and a scheduled backup is relative to when the server started, not to a clock time.
- A full backup copies every blob, so on a large server it takes time and space; the pre-migration backup avoids that by skipping blobs, which a migration does not change.
- The archive format is a contract: changing it needs `circles-backup/2` and a new ADR, and restore must keep reading `/1`.
