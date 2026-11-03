# Load test: 300-member circle

- Machine: Intel(R) Core(TM) i7-3770 CPU @ 3.40GHz, 8 hardware threads, 30 GB RAM
- Server runtime: openjdk version "21.0.12.1" 2026-08-18
- Server: the shadow jar in its own JVM with `-Xmx256m`, rate limits off, listening on localhost; the load generator runs on the same machine, so the server shares the CPU with it

## The circle

- 300 members, 5 stewards, 600 items each with sealed detail and one 250 KiB photo blob, 1500 completed loans
- 9603 events in the log, built through the public API in 29 s
- On disk: database and write-ahead log 10.7 MiB (1169 bytes per event), blobs 146.5 MiB

## Steady state and ramp

Every member holds an event stream open (so 300 streams). A hint triggers a pull after a random delay of up to 2 s, as a client would debounce. Writers push one sealed event per request. About 5 photo downloads per second run alongside.

| Writes/s asked | Writes/s done | Push p50 / p95 / p99 (ms) | Hint reaches a member p50 / p95 (ms) | Pull p95 (ms) | Event pulled, incl. debounce p50 / p95 (ms) | Errors | Server CPU (cores) | Server RSS peak | Threads |
|---|---|---|---|---|---|---|---|---|---|
| 2 | 1.8 | 11 / 23 / 26 | 14 / 32 | 3 | 687 / 1699 | 0 | 0.38 | 275 MiB | 118 |
| 25 | 25.0 | 9 / 14 / 16 | 11 / 15 | 6 | 611 / 1590 | 0 | 1.06 | 284 MiB | 115 |
| 50 | 50.0 | 10 / 18 / 26 | 10 / 17 | 8 | 612 / 1579 | 0 | 1.89 | 287 MiB | 117 |
| 100 | 100.0 | 15 / 37 / 59 | 17 / 38 | 31 | 629 / 1619 | 0 | 3.29 | 371 MiB | 118 |

## A new device catches up

Pulling the whole log (13157 events) in 27 pages: 0.2 s, 7.7 MiB downloaded.

## After the load

- Idle server memory (RSS): 377 MiB, 118 threads
- On disk: database and log 14.2 MiB, blobs 146.5 MiB

## Operator tools (run while the server is up and idle)

- `backup`: 5.4 s, archive 152.2 MiB
- `verify` of that backup: 1.3 s
- `export`: 5.2 s, file 150.2 MiB
- `verify` of that export: 13.3 s
