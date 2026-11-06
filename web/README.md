# web

The browser client (ADR 0009): Kotlin/JS with `kotlinx-html`, no TypeScript. This module is only views and browser glue. All behaviour (sign-in, joining, items, borrowing, standing, stewards, recovery, export and the rest) lives in `circles.app.CircleApp` and the other packages of `shared`, where it is tested on JVM and JS.

## Screens

Welcome, Start a circle, Join a circle, Sign in, Home, Members, Items (list, add, change), Borrow (list, request), Standing, Skills (list, offer, change), Pauses, Stewards (rules, recovery shares), Settings, Recovery code and Restore, pairing of a second device, and the Verify and Item tag pages with QR codes. Screens are organised by topic; each screen has one purpose (ADR 0057).

## Files

In `src/jsMain/kotlin/circles/web`:

- `Screen.kt` the screens and fragment routing (`#/items`).
- `AppState.kt` pure state, actions, `reduce`, and the sync status sentence.
- `ShellView.kt` every page as a function of state, written through any `kotlinx-html` consumer so tests render it to a string.
- `Main.kt` the only browser glue: mounts the views, wires `data-action` buttons and forms by event delegation, listens for `hashchange`/`online`/`offline`, moves focus to `main` on a screen change, registers the service worker. `Page.busyLaunch(button) { ... }` wraps every click and submit: it sets `aria-busy` (spinner, clicks ignored) and clears it when the action ends. New async actions must go through it.
- `Appearance.kt` text size and theme, kept in localStorage and applied as `data-text` and `data-theme` on `<html>`.
- `ImagePrep.kt` decodes and re-encodes item photos in the browser (the policy is in `shared`).

In `src/jsMain/resources`: `index.html` (with an opening spinner), `styles.css`, `sw.js` (the service worker, the only hand-written JS, ADR 0041), `wordlist.txt` and `wordlist.LICENSE.txt` (the recovery-code word list, a served file, not part of the bundle).

Rules: logic never depends on the DOM (it is tested in Node); the browser test task is off. Local storage is IndexedDB with verify-once ingest (ADR 0036); crypto is noble (ADR 0035); the wire JSON on the page's path is hand-written (ADR 0054).

## Build and test

```
./gradlew :web:jsNodeTest                 # view and logic tests, in Node
./gradlew :web:jsBrowserDevelopmentRun    # dev server with reload
./gradlew :web:jsBrowserProductionWebpack # production bundle in build/kotlin-webpack/js/productionExecutable
./gradlew :web:webDist                    # the deployable folder, build/dist/web
./gradlew :web:stampWorker                # webDist, then writes a hash of the shell files into sw.js
./gradlew :web:checkBundleBudget          # fails if circles.js gzipped is over 300 KiB; part of `check`
```

`webDist` assembles `circles.js` and the static files into `build/dist/web`. `stampWorker` replaces `__BUILD_ID__` in `sw.js` with a SHA-256 prefix, so a changed shell is a changed worker; the server packs the output of `stampWorker`. The worker caches `/`, `/circles.js` and `/styles.css`, never touches `/api/`, and waits for the member to press "Update now" before taking over.

## Bundle size

The budget is 300 KiB gzipped (`bundleBudgetBytes` in `build.gradle.kts`; ADR 0064 raised it from 250 KiB). Measured on 2026-11-06 from `build/dist/web/circles.js`: 856,617 bytes raw, 244,669 bytes gzipped (about 239 KiB). `webpack.config.d/minify.js` runs three Terser passes. `webpack.config.d/noble-shims.js` writes a one-line ES module per noble package that re-exports only the names Kotlin calls, so webpack drops the rest; when you add a noble function, add its name to the `used` list there, or production will have it undefined. `tools/bundle-analysis` shows where the bytes go.

## How the server serves it

The server packs the stamped `web/build/dist/web` into the shadow jar under `web/` and serves it at `/` with a strict content security policy (ADR 0037); the Ktor compression plugin gzips it (ADR 0058). To run it:

```
./gradlew :server:shadowJar
java -jar server/build/libs/lendger-server.jar serve    # then open http://localhost:8080
```

`:server:run` also works. Passkey unlock needs a real domain name or `localhost`, not an IP address.

## Checks in a real browser

`tools/browser-e2e` drives real Chrome profiles against the jar (the whole flow, axe-core accessibility checks with `A11Y=1`, the service worker, first-visit load time). See `tools/browser-e2e/README.md`. Not part of `./gradlew check`; CI runs it in a separate `browser` job.
