# Moving a circle to another host, or rebuilding one after a loss

A circle does not depend on one server. Every member's device holds the circle, and the host can be replaced. Which steps you need depends on what you still have.

| What you still have | What to do |
|---|---|
| The old host is running, and you can run commands on both | **A**: export, then import |
| The old host is running, but a steward does the move from their app | **B**: a token, then a signed import |
| The old host is gone, you have a backup | **C**: restore the backup |
| The old host is gone, no backup | **D**: rebuild from a member's copy |

Moving the web address is the easiest way to keep members connected: point the same name at the new host (change the DNS record) and their devices carry on, logging in again with the keys they already have, from where they stopped.

## First: find the circle's id

```bash
java -jar circles-server.jar list
# circle id                         created     events  devices  stewards
# 3f9a...                           2026-10-01    9603      300         5
```

## A. The old host is running: export and import

1. Install the new host ([install.md](install.md)) and run `migrate` once so it has an empty database.
2. On the **old** host (works while it is running):
   ```bash
   java -jar circles-server.jar export <circleId> circle.zip
   java -jar circles-server.jar verify circle.zip          # prints OK: N events and M blobs ...
   ```
3. Copy `circle.zip` to the new host (a circle of 300 members with 150 MiB of photos is a file of about 150 MiB).
4. On the **new** host:
   ```bash
   java -jar circles-server.jar import circle.zip
   ```
   It re-checks every event with the circle's own rules before and while writing, so a damaged or tampered file is refused. It prints `Imported N new events ... and M new blobs ...`. Running it again is harmless, and a later, bigger export extends an earlier import.
5. Switch the web address to the new host. Members' devices reconnect.
6. When you are sure all is well, stop the old host. Keep its last backup for a while.

Events keep their numbers, so devices do not notice the move.

## B. A steward moves the circle from their app

This lets a steward bring a circle onto your host without you handling the file, once you have said yes. It needs the app (not built yet); the protocol is in [docs/api.md](../api.md).

1. You decide the circle may come, and make a one-time token for it:
   ```bash
   java -jar circles-server.jar import-token <circleId> --hours 24
   ```
   Send the printed token to the steward over a private channel. It works **once**, for **that circle only**, and expires.
2. The steward's app exports the circle from the old host (their signed request) and sends it to your host with the token.
3. A circle your host already has needs no token: its stewards can extend it directly.

The host must be reached over HTTPS, because the token travels in a header.

## C. The old host is gone and you have a backup

Install a new host, stop it if it started, and restore ([backup-restore.md](backup-restore.md)):

```bash
java -jar circles-server.jar restore circles-backup-<time>.zip
```

Point the web address at the new host. Anything sent after the backup was taken is still on members' devices and arrives again when they sync.

## D. The old host is gone and there is no backup

Any member's device holds the circle's whole history. Using the same file format as above (`circles-export/1`), a member's app can export what it has, and you import it like in step A:

```bash
java -jar circles-server.jar verify circle-from-member.zip
java -jar circles-server.jar import circle-from-member.zip
```

Pick the member whose app was most recently up to date. The format and the import are ready and tested; the app's "export circle" button is not built yet, so for now this path needs a developer to produce the file.

## When a steward is leaving

That is done inside the app, not on the host: the other stewards (or the leaving steward, while another remains) change the stewards, rotate the circle key, and re-split the recovery shares. After the circle's stewards say it is done, the departing person has no special access to the host's data: the host has none to give.

## After any move: check

- `list` on the new host shows the circle with the same number of events.
- A member can open the app, see recent activity and send something.
- Photos appear.
- The backup folder on the new host gets its first scheduled backup within a day.
