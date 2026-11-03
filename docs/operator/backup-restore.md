# Backups and restoring

## What is in a backup, and what is not

A backup is one zip file holding the whole database and every photo. It holds **ciphertext and the public information the server already has**. It does not hold any key: the circle's keys live on members' devices, so after a restore their devices simply log in again with the keys they already have, and carry on.

Treat the files as private. The tool writes them readable by their owner only. They hold no passwords or login sessions (those are stripped out), but they do show who is in a circle and when things happened.

## Automatic backups

- **Every day.** With `CIRCLES_BACKUP_INTERVAL_HOURS=24` and `CIRCLES_BACKUP_DIR` set, the running server writes `scheduled-<time>.zip` into that folder every 24 hours and keeps the newest `CIRCLES_BACKUP_KEEP` (7 by default). The first one is written one interval after the server starts, not at start-up. Other files in that folder are never deleted.
- **Before every upgrade.** `migrate` writes `pre-migrate-from-v<N>-<time>.zip` first, and refuses to change anything if it cannot ([upgrade.md](upgrade.md)).
- **Where the folder is matters.** A backup on the same disk as the data is lost with it. Point `CIRCLES_BACKUP_DIR` at a different disk or a mounted network drive, and also copy the files off the machine regularly, for example:

```bash
rsync -a /srv/circles-backups/ backup-server:/srv/backups/circles/        # every night, from cron
```

## Making a backup by hand

It is safe while the server is running and takes a few seconds (about 6 seconds for a circle of 300 members with 150 MiB of photos).

```bash
# Docker:
docker compose -f deploy/compose.yaml exec circles java -Xmx256m -jar /app/circles-server.jar backup /backups
# Plain jar:
sudo -u circles env $(cat /etc/circles/circles.env | xargs) java -jar /opt/circles/circles-server.jar backup /srv/circles-backups
```

It prints the file it wrote, `circles-backup-<time>.zip`. It never overwrites an existing file, and if a photo is missing on disk it stops with an error instead of making a backup that is quietly incomplete.

## Checking a backup

```bash
java -jar circles-server.jar verify circles-backup-20261103024657.zip
```

`verify` does not need a database or a running server and can be run on another machine. It checks every checksum, that nothing extra is in the file, that the database opens and passes SQLite's own integrity check, and that this server is not older than the backup. It reports every problem it finds. On success it prints `OK: backup of schema 7 with N blobs`. Run it on your newest backup now and then.

## Restoring

Restoring replaces the server's data with the backup's, so **stop the server first**.

**On a new, empty host** (the usual case after a loss):

```bash
# Docker, with the backup file in the folder mounted at /backups:
docker compose -f deploy/compose.yaml run --rm circles restore /backups/circles-backup-20261103024657.zip
# Plain jar:
java -jar circles-server.jar restore circles-backup-20261103024657.zip
```

Then start the server. Members' devices reconnect on their own, using the same web address.

**On a host that already has data**, `restore` refuses and says so. Add `--force`:

```bash
java -jar circles-server.jar restore circles-backup-20261103024657.zip --force
```

With `--force` nothing is deleted. The existing database is renamed to `circles.db.before-restore-<time>` (with its `-wal` and `-shm` files) and the existing `blobs/` folder to `blobs.before-restore-<time>`, and the command prints what it moved. Delete them yourself when you are satisfied.

**If the backup is from an older version** (for example the `pre-migrate-...` file taken before an upgrade), `restore` puts it back as it was and tells you to run `migrate` next, which will take its own backup first. A backup from a **newer** version than your server is refused: install the newer server.

**Pre-upgrade files from an older build** may have been database-only (no photos); restoring one of those replaces the database and keeps the photos already on disk.

If anything is wrong with the file, `restore` says what and changes nothing: it prepares everything under temporary names and only moves it into place at the end.

## Practice once

A backup you have never restored is a hope, not a backup. On a spare machine (or a second Docker project), restore your latest backup into an empty folder, start the server, and check `list` shows your circle with the event count you expect. Then throw the spare away.

```bash
java -jar circles-server.jar list          # circle id, date created, events, devices, stewards
```

## How big and how long

For 16,000 events and 150 MiB of photos on a 2012 desktop processor: backup 5 to 6 seconds, 154 MiB; verify 1.5 seconds; restore is about the same as the backup. Allow disk space for several backups, each about the size of the database plus the photos. See [requirements.md](requirements.md).
