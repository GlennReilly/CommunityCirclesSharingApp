# Running a Circles host

For the person who looks after the server. You do not need to read the code. Read in this order the first time:

1. [install.md](install.md): put a server on a machine and give it HTTPS.
2. [requirements.md](requirements.md): how much machine you need, from measurements.
3. [backup-restore.md](backup-restore.md): backups, and how to prove they work.
4. [upgrade.md](upgrade.md): installing a new version safely.
5. [handover.md](handover.md): moving a circle to another host, or rebuilding it after a loss.

Two things to know before anything else:

- **You can see metadata but not content.** Members' notes, names, items and photos are encrypted on their devices; the server stores ciphertext and never holds a key. You can see who is a member, which devices they use, when and how often events are sent, and the size of files. Treat the database and backups as private for that reason.
- **Your backups matter more than your server.** The circle lives on every member's device too, but a restore from a backup is the fast, complete way back. Set up backups on day one.

How these were checked: the Docker image was built and run, and the backup, restore, export, import and verify commands were run against real servers (in tests and in the load test). The systemd and plain-jar steps and public HTTPS were not run on a live machine; see [deploy/README.md](../../deploy/README.md).
