# web

The browser client (ADR 0009): Kotlin/JS with `kotlinx-html`, no TypeScript. This is the M3 skeleton; the screens say "not built yet".

- `Screen.kt` the nine v1 screens and fragment routing (`#/items`).
- `AppState.kt` pure state, actions, `reduce`, and the sync status sentence ("Offline, 3 changes waiting").
- `ShellView.kt` the page shell as a function of state, written through any `kotlinx-html` consumer so tests render it to a string.
- `Main.kt` the only browser glue: mounts the shell, listens for `hashchange`/`online`/`offline`, moves focus to `main` on a screen change.
- `src/jsMain/resources` `index.html` and `styles.css` (focus rings, reduced motion, light and dark).

Rules: logic never depends on the DOM (it is tested in Node with `:web:jsNodeTest`); the browser test task is off. Local storage is IndexedDB with verify-once ingest (ADR 0036); crypto is noble (ADR 0035).

```
./gradlew :web:jsNodeTest                 # logic and view tests
./gradlew :web:jsBrowserDevelopmentRun    # dev server with reload
./gradlew :web:jsBrowserProductionWebpack # bundle in build/kotlin-webpack/js/productionExecutable (+ index.html in build/processedResources/js/main)
./gradlew :web:checkBundleBudget          # fails if circles.js gzipped is over 300 KiB; part of `check`
```

The budget check measures what is actually bundled. `shared` is a dependency but unused so far, so the number (about 19 KB gzipped) will grow as the client starts using the fold, the crypto and the sync code; ADR 0009 expects roughly 100 KB for the Kotlin side and 22 KB for noble.

The server serves the bundle at `/` (ADR 0037): `./gradlew :server:shadowJar`, then `java -jar server/build/libs/circles-server.jar serve` and open http://localhost:8080 (`:server:run` also works).

The sync engine, `IndexedDbReplica` and `FetchTransport` live in `shared` (ADR 0038) and are tested there; the shell shows the engine's `SyncState`.

Sign-in, joining by invite link, unlock/lock and the Home screen work end to end (see `tools/browser-e2e`). Not built yet: service worker, the other eight screens, passkey wrapping, the recovery code, the event-stream reader, an axe check in CI.
