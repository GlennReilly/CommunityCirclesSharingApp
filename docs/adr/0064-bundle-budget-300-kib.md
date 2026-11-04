# 0064. The script budget rises from 250 KiB to 300 KiB

- Status: Accepted
- Date: 2026-11-04
- Supersedes the 250 KB figure in ADR 0009 and spec section 6 (the rest of ADR 0009 stands)

## Context

The budget caps `circles.js` gzipped. It was set at 250 KB when the app was a skeleton, as a guard for slow phones on poor networks. Every planned screen is now built and the bundle is 242 KB after dropping the conflated channels in `SyncScheduler` (a 9.7 KB saving). Three diets (noble shims, hand-written JSON, channels) have already run; what remains is Kotlin and its runtime (about 160 KB) and 41 KB the source map cannot place. Further cuts would mean dropping features or hand-rolling code that libraries do well (coroutines, the QR encoder checks, image policy), which is the poor trade the owner asked us not to make.

Measured cost of size (`tools/browser-e2e/load-e2e.mjs`, bare jar, 4x slower CPU): first visit 2.0 s on fast 3G and about 6 s on slow 3G at 243 KB. The service worker (ADR 0041) caches the script, so this is paid once per version, not per visit. Each 50 KB gzipped adds roughly 1 s on slow 3G.

## Decision

Raise the budget to 300 KiB (307,200 bytes) gzipped. Keep the check in `check` so growth is still visible. Keep dieting when it is cheap and safe, but do not trade correctness, security or accessibility for bytes. Revisit at 300 KiB with a measurement, not a reflex.

## Consequences

- About 65 KB of headroom: room for the remaining polish (appearance settings, pause history) without more diets.
- Slow-3G first visit grows by about 1 s at the limit; still acceptable for a one-time install.
- `web/build.gradle.kts`, `web/README.md` and CLAUDE.md state the new figure.
