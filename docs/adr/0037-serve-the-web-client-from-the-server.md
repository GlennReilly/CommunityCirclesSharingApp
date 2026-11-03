# 0037. Serve the web client from the server, same origin

- Status: Accepted
- Date: 2026-11-03

## Context

The spec leaves open where the PWA is hosted. The client talks to one server, holds the user's keys, and must make no third-party calls. A separate static host would mean a second thing for a volunteer operator to run, CORS rules on the API, and a second origin to trust. The server already ships as one jar (ADR 0017), and the client bundle is small (19 KB gzipped today, 250 KB budget).

## Decision

1. **The shadow jar carries the client** under `web/` (`index.html`, `circles.js`, `styles.css`), built by Gradle from the `:web` module (`:web:webDist`). The server serves it at `/` from the classpath. `:server:run` gets the same files on its classpath.
2. **Same origin.** The client calls the API with relative URLs; no CORS headers exist or are added.
3. **Explicit routes win.** `/api`, `/healthz`, `/readyz` and `/version` are matched first; the static handler only sees what is left, and answers 404 for unknown files. There is no fallback to `index.html`, because screens are chosen by URL fragment (`#/items`).
4. **Headers on every client file:** `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and `Cache-Control: no-cache` (the browser revalidates, so an upgraded server never serves an old script). The policy allows no inline script, no inline style and no other origin.
5. **If the jar has no bundle** (a plain build from sources that skipped the web module) the server serves only the API, as before.
6. **A request path containing `..` that reaches the static handler is answered 400**, not 500: `BadRequestException` is now mapped to a 400 problem for the whole server. This changed one existing behaviour: a blob request with `..` in the hash used to get 404 and now gets 400 (test updated).

## Consequences

- One artifact to deploy and back up; the client version always matches the server that serves it.
- The jar grows by about 60 KB now and by the bundle's size as the client grows; building the jar now needs the JavaScript toolchain (Node is downloaded by Gradle).
- The strict policy means the client may not use inline styles or scripts, or third-party fonts and analytics. The service worker (still to come) must be a separate file served from `/`.
- Hosting the client elsewhere (a CDN) later is possible but would need CORS on the API and a new ADR.
- The Docker context still only needs the jar.
