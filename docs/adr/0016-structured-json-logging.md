# 0016. Structured JSON logging without a logging framework

- Status: Accepted
- Date: 2026-11-02

## Context

The spec asks for JSON logs on stdout (section 7) and a test that "logs contain no keys or plaintext payloads" (section 8). The server had no SLF4J provider, so it logged nothing. A logging framework with a JSON encoder adds several megabytes and a configuration language; most of what it logs (framework internals) is not what an operator of a small circle needs, and it is the likeliest place for a secret to leak.

## Decision

A small logger in the server (`Log`), written to stdout as one JSON object per line: `time` (UTC, ISO 8601), `level`, `event`, then fields. The level comes from `CIRCLES_LOG_LEVEL` (`debug`, `info`, `warn`, `error`; default `info`). Only the server's own code logs, with events chosen by us: start-up, migrations applied, one `request` line per HTTP call, a summary per push, `rate_limited`, and `unhandled_error`. Ktor and Netty internals stay silent.

Never logged, by construction (the logger takes named fields, not requests): bodies, headers (including `Authorization`), query strings, client addresses, tokens, nonces, signatures, keys, payload bytes, and exception messages. Paths are logged with 32-character ids replaced by `{id}`. An unhandled error logs the exception class and its first frames only.

## Consequences

No new dependency and a leak-resistant default. Operators get less detail than a debug-level framework log; adding a field means editing code and its test. If a future operator needs a standard framework, `Log` is the one place to change.
