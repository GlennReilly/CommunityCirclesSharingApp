# 0032. An explicit backup folder, and a full backup before every migration

- Status: Accepted
- Date: 2026-11-03

## Context

ADR 0031 put scheduled and pre-migration backups in `<dataDir>/backups` by default and made the pre-migration backup database-only. The owner pointed out two weaknesses. A default folder inside the data directory dies with the disk it is meant to protect against, and operators who never think about it are left with backups that give no protection from a disk failure. And a database-only backup before a migration is not a complete undo if something goes wrong with the blobs.

## Decision

1. **`CIRCLES_BACKUP_DIR` is required for the two automatic kinds of backup.** It names the folder for scheduled backups (`scheduled-<time>.zip`) and pre-migration backups (`pre-migrate-from-v<N>-<time>.zip`). There is no default and no fallback to the data directory.
   - If `CIRCLES_BACKUP_INTERVAL_HOURS` is set and `CIRCLES_BACKUP_DIR` is not, the server refuses to start with a configuration error saying so.
   - If `migrate` would change an existing database and `CIRCLES_BACKUP_DIR` is not set, it refuses to migrate and says to set it, ideally to a folder on another disk.
   - A brand-new database, or one that is already up to date, needs neither.
2. **The pre-migration backup is a full backup**: the database and every blob, like `backup <dir>`, so restoring it is a complete undo. If it fails for any reason, the migration is not run.
3. **There is no switch to skip the backup.** An operator with their own backup mechanism sets `CIRCLES_BACKUP_DIR` to any folder they like (a mounted volume, a throwaway folder) and deletes the archive.
4. **Unchanged from 0031:** the archive format, snapshot method, `verify`, `restore` and its `--force` rules, retention by `CIRCLES_BACKUP_KEEP`, and `backup <dir>`, which has always taken an explicit folder.

## Consequences

- A fresh install cannot silently run without off-disk backups configured: scheduling needs the folder, and the first migration after an upgrade will not proceed until one is set.
- A migration on a large server now copies every blob first, which takes time and space; the benefit is a complete undo.
- Operators who truly do not want a backup have to point the folder somewhere and discard the files; that friction is deliberate.
- Amends ADR 0031 points 6, 7 and 9; the rest of 0031 stands.
