# Upgrading

A new version of the server may change the database. The server never changes a database by surprise: `serve` refuses to start on an old database and tells you to run `migrate`, and `migrate` takes a full backup before it touches anything.

## Before you start

- Read the release notes for anything to do by hand.
- Check `CIRCLES_BACKUP_DIR` is set. **`migrate` refuses to upgrade an existing database without it**, because it must be able to take the backup first.
- Make sure there is disk space for one more full backup (about the size of the database plus the photos).

## Steps

1. **Back up now**, and check it ([backup-restore.md](backup-restore.md)):
   ```bash
   docker compose -f deploy/compose.yaml exec circles java -Xmx256m -jar /app/circles-server.jar backup /backups
   ```
2. **Stop the server.** Docker: `docker compose -f deploy/compose.yaml stop`. Jar: `sudo systemctl stop circles-server`.
3. **Install the new version.** Docker: build the new jar (`./gradlew :server:shadowJar`) and `docker compose -f deploy/compose.yaml build`. Jar: replace `/opt/circles/circles-server.jar`.
4. **Migrate.**
   ```bash
   docker compose -f deploy/compose.yaml run --rm circles migrate      # Docker
   java -jar /opt/circles/circles-server.jar migrate                   # jar, with the settings in the environment
   ```
   If the database needs changing it prints `Backed up the database and N blobs to ...` and then `Applied migrations ...`. If it prints `Database is up to date` there was nothing to do. If the backup cannot be written it says `the database was not migrated` and stops: fix the folder and run it again.
5. **Start** the server and look: `curl http://127.0.0.1:8080/version`, then the logs for errors, then open the app and send something.

## If it goes wrong

Stop the new server and go back to the old one:

1. Install the **old** version again (its jar or image).
2. Restore the backup `migrate` took, which is the whole old state:
   ```bash
   java -jar circles-server.jar restore /srv/circles-backups/pre-migrate-from-v5-<time>.zip --force
   ```
3. Start the old server.

Anything members did after the upgrade and before you rolled back is lost from this host; it is still on their devices and is sent again when they next sync, as long as it is not rejected by the older rules.

## Good to know

- A server **older** than the database refuses to start (`the database is at version N, newer than this server`). Do not run an old jar on a migrated database; restore a pre-migration backup instead.
- Docker keeps the data in a named volume (`circles-data`), so rebuilding the image does not touch it.
- Upgrading is quick for small circles; for one with a lot of photos the pre-upgrade backup is most of the time (about 6 seconds for 150 MiB).
