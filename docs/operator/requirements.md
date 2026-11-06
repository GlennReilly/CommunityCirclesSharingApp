# What a host needs to run Lendger

These numbers come from a load test, not from guesses: [`docs/operator/load-test-512mb.md`](load-test-512mb.md) and [`load-test-256mb.md`](load-test-256mb.md) are the raw reports, measured on 2026-11-03 on an Intel Core i7-3770 (2012; 4 cores, 8 threads), 32 GB RAM, Java 21. To repeat the test on your own hardware:

```bash
./gradlew :server:loadTest -Pload.members=300 -Pload.heapMb=256     # about 5 minutes; report in server/build/load-test/report.md
```

## The short version

For one circle of up to **300 members**:

| | Enough | Comfortable |
|---|---|---|
| CPU | 1 core | 2 cores |
| RAM for the server | 512 MiB (run Java with `-Xmx256m`) | 1 GiB (`-Xmx512m`) |
| Disk | database plus blobs, see below, and the same again for backups | 2 x that |
| Network | a normal home or small-business connection | the same |

A real circle is far quieter than the test, so these are conservative. Rate limits were switched off in the test and are on by default.

## What the test did

It builds a circle through the public API against the real server jar running in its own JVM: **300 members, 5 stewards, 600 items** (each with sealed detail and a 250 KiB photo), and **1,500 completed loans**: about 9,600 events. Then every member held an event stream open, and writers pushed sealed events at 2, 10, 25, 50, 100 and 200 per second, with photo downloads and a debounced pull by every member running alongside.

For scale: 300 members each doing something once an hour is about 0.08 writes per second. The test's slowest step, 2 per second, is 25 times busier than that.

## Results

| Measured | 512 MiB heap | 256 MiB heap |
|---|---|---|
| Errors at any rate up to 100 writes/s | none | none |
| Push time, 25 writes/s (median / 95th percentile) | 10 / 14 ms | 9 / 14 ms |
| Push time, 100 writes/s | 32 / 125 ms | 15 / 37 ms |
| A "new event" hint reaches a member (95th percentile), up to 50 writes/s | 28 ms or less | 32 ms or less |
| Server CPU, 2 / 25 / 100 writes/s (cores) | 0.35 / 1.0 / 3.0 | 0.38 / 1.1 / 3.3 |
| Server memory (resident), steady / peak | 417 / 427 MiB | 275 / 377 MiB |
| A brand-new device pulls the whole log | 0.4 s, 9.6 MiB (16,198 events) | 0.2 s, 7.7 MiB (13,157 events) |

- **Memory:** 256 MiB of Java heap was enough, with no out-of-memory and the same response times. The resident figure is higher than the heap because of Java's own overhead and thread stacks (about 120 threads with 300 streams open), so allow about 400 MiB for the process at `-Xmx256m`, and set the machine's RAM above that.
- **CPU:** time to respond stayed in the tens of milliseconds up to about 50 writes per second. At 100 per second the server used three cores of a four-core machine that was also running the load generator, and in the 512 MiB run it did not reach 200 per second (139 done), which is the machine's limit with both on it, not a proven ceiling for the server alone.
- **Latency:** a member learns of a new event within a few tens of milliseconds. The "pulled" column in the raw reports is higher because the test deliberately waits up to 2 seconds before pulling, as a client should.

## Disk

- **Database:** about **1.2 KB per event** including its indexes. The 9,600-event circle was 10.8 MiB; after the load test added 6,600 more events it was 17.4 MiB. A circle at one event per member per hour is roughly 1.2 KB x 300 x 24 x 365 = **3 GB a year**, so in practice the photos dominate long before the events do.
- **Blobs (photos):** whatever members upload, up to `LENDGER_BLOB_QUOTA_MIB` per circle (256 by default). The test's 600 photos took 146.5 MiB. Clients shrink photos before upload ([ADR 0028](../adr/0028-client-image-policy.md)), so a typical photo is about 150 to 400 KB.
- **Backups:** a full backup is the database plus every blob (154 MiB for the test circle); keep several, and keep them on another disk (`LENDGER_BACKUP_DIR` is required, [ADR 0032](../adr/0032-explicit-backup-folder-and-full-pre-migration-backup.md)).

## Network

- A device that is catching up from nothing downloads about **600 bytes per event** (9.6 MiB for 16,198 events).
- A photo is a download of its own size when someone opens it.
- A stream hint is about 30 bytes, and an idle stream sends a few bytes every 25 seconds, so 300 open streams cost almost nothing.
- Each steady write is a push of a few hundred bytes to a few kilobytes.

## How long the tools take (16,000-event, 154 MiB circle, server running and idle, 256 MiB heap)

| Command | Time | Output |
|---|---|---|
| `backup` | 5 to 6 s | 152 to 154 MiB |
| `verify` a backup | 1.3 to 1.5 s | |
| `export` | 5 s | 150 to 151 MiB |
| `verify` an export | 13 to 17 s | (it re-checks every signature and replays the whole log) |

They all ran fine in a 256 MiB heap and, since the fix below, can run while the server is up.

## What these numbers do not tell you

- **One machine, localhost.** There was no network between the load generator and the server, and the generator used some of the same CPU. A real network adds latency; a newer CPU is faster than this 2012 one.
- **Synthetic writes.** The writers send sealed `member.invite` events (the same server path as any other event: signature check, insert, hint) rather than a realistic mix. Loans, items and detail were real, but only during setup.
- **One circle.** Several circles on one host share the CPU and the disk; add their loads.
- **Rate limits off.** With them on, a single address is limited to 60 pushes per minute and so on ([ADR 0015](../adr/0015-rate-limiting-pulled-forward.md)); that protects the host and does not apply to real circles at normal use.
- **Not measured:** many circles at once, a cold disk, a slow disk, and TLS.

## A bug the test found

The first full run showed that the running server held SQLite's write lock for its whole life, so `export`, `import-token` and `migrate` failed with "database is locked" while it was up. That is fixed (the store now takes the lock only inside a transaction) and is covered by regression tests.
