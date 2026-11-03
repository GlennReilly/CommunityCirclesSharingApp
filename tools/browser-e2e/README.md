# Browser end-to-end check

Drives two real Chrome profiles against the real server jar over the Chrome DevTools Protocol (no npm packages; Node 22+ for the global `WebSocket` and `fetch`). It is not part of `./gradlew check` because it needs Chrome and a built jar.

```
./gradlew :server:shadowJar
node tools/browser-e2e/e2e.mjs
```

Needs `google-chrome` and `java` on the PATH (set `CIRCLES_JAR` to use another jar). It starts the server on 127.0.0.1:18081 with a temporary data folder and two headless Chrome instances on debugging ports 9333 and 9334, and stops them at the end.

What it checks, in order: a short passphrase is refused; Alice starts a circle and it syncs; she makes an invite link; Bea opens the link (the secret is removed from the address bar), joins, and waits; Alice sees her waiting and lets her in; Bea can then read; Alice locks, a wrong passphrase is refused, a reload leaves her locked, the right passphrase unlocks with the circle intact; the stored identity is only the wrapped blob. Any console error or exception is printed at the end.
