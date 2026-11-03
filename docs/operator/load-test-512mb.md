# Load test: 300-member circle

- Machine: Intel(R) Core(TM) i7-3770 CPU @ 3.40GHz, 8 hardware threads, 30 GB RAM
- Server runtime: openjdk version "21.0.12.1" 2026-08-18
- Server: the shadow jar in its own JVM with `-Xmx512m`, rate limits off, listening on localhost; the load generator runs on the same machine, so the server shares the CPU with it

## The circle

- 300 members, 5 stewards, 600 items each with sealed detail and one 250 KiB photo blob, 1500 completed loans
- 9603 events in the log, built through the public API in 28 s
- On disk: database and write-ahead log 10.8 MiB (1181 bytes per event), blobs 146.5 MiB

## Steady state and ramp

Every member holds an event stream open (so 300 streams). A hint triggers a pull after a random delay of up to 2 s, as a client would debounce. Writers push one sealed event per request. About 5 photo downloads per second run alongside.

| Writes/s asked | Writes/s done | Push p50 / p95 / p99 (ms) | Hint reaches a member p50 / p95 (ms) | Pull p95 (ms) | Event pulled, incl. debounce p50 / p95 (ms) | Errors | Server CPU (cores) | Server RSS peak | Threads |
|---|---|---|---|---|---|---|---|---|---|
| 2 | 1.9 | 12 / 24 / 28 | 14 / 28 | 3 | 685 / 1698 | 0 | 0.35 | 417 MiB | 121 |
| 10 | 10.0 | 10 / 16 / 18 | 13 / 19 | 5 | 629 / 1618 | 0 | 0.57 | 419 MiB | 119 |
| 25 | 25.0 | 10 / 14 / 17 | 11 / 15 | 5 | 606 / 1584 | 0 | 1.02 | 420 MiB | 119 |
| 50 | 50.0 | 11 / 19 / 28 | 11 / 19 | 9 | 606 / 1586 | 0 | 1.83 | 421 MiB | 119 |
| 100 | 100.0 | 32 / 125 / 164 | 32 / 122 | 119 | 674 / 1693 | 0 | 2.97 | 422 MiB | 119 |
| 200 | 139.2 | 117 / 159 / 224 | 123 / 168 | 158 | 732 / 1739 | 0 | 3.08 | 423 MiB | 119 |

## A new device catches up

Pulling the whole log (16198 events) in 33 pages: 0.4 s, 9.6 MiB downloaded.

## After the load

- Idle server memory (RSS): 427 MiB, 119 threads
- On disk: database and log 17.4 MiB, blobs 146.5 MiB

## Operator tools (run while the server is up and idle)

- `backup`: 5.8 s, archive 153.9 MiB
- `verify` of that backup: 1.5 s
- `export`: 5.4 s, file 151.1 MiB
- `verify` of that export: 16.9 s
