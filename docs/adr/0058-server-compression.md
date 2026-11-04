# 0058. The server compresses text itself

- Status: Accepted
- Date: 2026-11-04

## Context

The 250 KB budget for the page's script (spec section 6, ADR 0009) is a gzipped size, but the jar sent `circles.js` raw (about 790 KB). Only an installation with Caddy in front (`deploy/Caddyfile`, `encode zstd gzip`) delivered the gzipped size, and a bare jar on a home network or behind a proxy that does not compress did not. The measurement in `tools/browser-e2e/load-e2e.mjs` showed the difference.

## Decision

The server installs Ktor's `Compression` plugin with gzip only, for replies of at least 1 KB, sent only to clients whose `Accept-Encoding` allows it. It never compresses `text/event-stream` (the live update stream flushes every message) or `application/octet-stream` (blobs are encrypted and do not compress). A proxy that compresses too is harmless: it leaves a reply that already has a `Content-Encoding` alone.

## Consequences

- A bare jar sends 226 KB for the 787 KB script (checked in Chrome with `DIRECT=1 node tools/browser-e2e/load-e2e.mjs`): the first-visit times in `tools/bundle-analysis/README.md` hold without a proxy. First visit with a 4x slower CPU: 2.0 s on fast 3G, 6.0 s on slow 3G.
- A small CPU cost per request for large replies; the page, scripts and the pull answers are the only ones that are large.
- The live-update check in the browser script (an item appearing by itself without a sync click) proves the stream still works with `Accept-Encoding: gzip`.
