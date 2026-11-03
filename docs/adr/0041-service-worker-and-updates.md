# 0041. Service worker: offline shell, no API caching, the member chooses when to update

- Status: Accepted
- Date: 2026-11-03

## Context

The app keeps its data locally and is meant to work offline (spec section 6). Without a service worker the page itself cannot load without the server. The spec asks for a versioned cache of the app shell, an update prompt, and no caching of API responses beyond the local replica. A service worker must be a separate script served from the origin root, so it cannot be part of the Kotlin/JS bundle without a second build target.

## Decision

1. **`sw.js` is a small hand-written JavaScript file** (about 40 lines, no dependencies) in the web module's resources, served at `/sw.js` like the other client files (ADR 0037), with the same strict policy. It is the only JavaScript we write by hand; ADR 0009 (no TypeScript, no second toolchain) otherwise stands.
2. **It caches exactly the shell:** `/`, `/circles.js`, `/styles.css`, in a cache named `circles-shell-<build id>`. The build id is a SHA-256 hash (first 16 hex characters) of those three files, written into `sw.js` by the `webDist` Gradle task, so any change to the shell changes the worker, and the browser notices.
3. **It never handles `/api/` requests or any non-GET or cross-origin request.** Page navigations to `/` are answered from the cache, falling back to the network; other server routes (`/healthz`, `/version`) are untouched.
4. **Updates are the member's choice.** A new worker installs in the background but does not take over (no `skipWaiting` on install). The page notices the waiting worker and shows the existing "A new version is ready. Update now" prompt; pressing the button tells the worker to take over, and the page reloads once the new worker controls it. The page also asks the browser to check for a new worker every hour. On first ever install there is no prompt.
5. **Old caches are deleted** when a new worker activates.
6. **A reload after an update locks the app** (keys live in memory only), so the member types their passphrase again.

## Consequences

- After one visit the page opens with no network, on the sign-in or welcome page, and syncs when the connection returns.
- A member is never switched to a new version in the middle of typing; the cost is that someone who ignores the prompt keeps the old version until they close all tabs.
- The service worker file is one more thing to review by hand; it has no logic beyond routing and cache names, and a browser test in `tools/browser-e2e` covers the offline load and the update prompt.
- The server's `Cache-Control: no-cache` on client files (ADR 0037) means the browser revalidates `sw.js` on each check, which is what makes updates arrive.
