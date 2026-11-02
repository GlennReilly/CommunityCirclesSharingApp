# 0015. Rate limiting pulled forward from M5

- Status: Accepted
- Date: 2026-11-02

## Context

ADR 0014 left rate limiting to M5 and said a public server should sit behind a limiting proxy. Circle creation and joining are unauthenticated and open by default, so a bare server can be filled with junk in minutes. The owner asked for basic limits now.

## Decision

An in-memory token bucket per key, using the limits in spec 4.3, plus one the spec lacks:

| Bucket | Key | Limit |
|---|---|---|
| auth (`/auth/challenge` and `/auth/session`) | client address | 10 per minute |
| push (`POST .../events`) | authenticated device | 60 per minute |
| join (`POST .../join`) | client address | 5 per minute |
| create (`POST /circles`) | client address | 5 per minute |

Each request counts once, whatever the number of events in a push; a login is two auth requests. Pull, probes and the rest are not limited. A refused request gets `429` problem+json with `Retry-After` in seconds. The client address is the socket peer; with `CIRCLES_TRUST_PROXY=true` it is the last entry of `X-Forwarded-For`, which is the address the trusted proxy itself saw (earlier entries can be forged by the client). `CIRCLES_RATE_LIMIT=false` switches limiting off, for operators who limit elsewhere. At most 10,000 keys are tracked; the least recently used is dropped first.

## Consequences

State is per process and resets on restart. People behind one shared address (a household, a mobile carrier) share the auth, join and create buckets. A distributed attacker is not stopped; a reverse proxy is still advised for a public server. This supersedes the "no rate limiting in M1" decision in ADR 0014; the rest of 0014 stands.
