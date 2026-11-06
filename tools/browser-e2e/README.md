# Browser end-to-end check

Drives real Chrome profiles (two, and a third for pairing a second device) against the real server jar over the Chrome DevTools Protocol (Node 22+ for the global `WebSocket` and `fetch`; the one npm dependency is `axe-core`, installed with `npm ci` in this folder). It is not part of `./gradlew check` because it needs Chrome and a built jar; CI runs it in a separate `browser` job.

```
./gradlew :server:shadowJar
(cd tools/browser-e2e && npm ci)
node tools/browser-e2e/e2e.mjs            # the whole flow
A11Y=1 node tools/browser-e2e/e2e.mjs     # the same, and axe-core on 26 page states (WCAG 2.0, 2.1, 2.2 A and AA); fails on any violation
```

Needs `google-chrome` and `java` on the PATH (set `LENDGER_JAR` to use another jar). It starts the server on 127.0.0.1:18081 with a temporary data folder and two headless Chrome instances on debugging ports 9333 and 9334, and stops them at the end.

What it checks, in order: the welcome page has choices only; field labels end in ": "; the passphrase button stays hidden until the passphrase is long enough; a short passphrase is refused; Alice starts a circle, is asked for her name first ("Your name: ", button hidden until typed) and it syncs; the Members page shows her fingerprint; she makes an invite link; Bea opens the link (the secret is removed from the address bar), joins, and waits; Alice sees her waiting (as "New member (waiting for a name)" with a fingerprint) and lets her in; Bea can then read and names herself; Alice adds a ladder with private details, Bea sees the name but not the details and is not offered Change, Alice renames it and then removes it and both lists follow; Bea asks to borrow it (dates and a note), Alice approves and Bea then sees the private details, both confirm the hand-over, Bea reports it back with damage and Alice sees that; Alice changes her passphrase (the old one then stops working) and her idle-lock time; a valuable item is shown as not yet open to the new member with the reasons, the reasons read "Drill needs Level 2; you are Level 1." with no tier identifier on the page, the standing pages show Level 1 and Level 3, and only the steward is offered Vouch; Bea cannot confirm names, Alice ticks that she checked the fingerprint in person (the button appears only then) and confirms Bea's name, which Bea then sees as confirmed; Alice locks, a wrong passphrase is refused, a reload leaves her locked, the right passphrase unlocks with the circle intact; the stored identity is only the wrapped blob. Any console error or exception is printed at the end.

## Sync gap check

```
node tools/browser-e2e/sync-gap-e2e.mjs
```

Holds the founder's first pull for 3 seconds (DevTools `Fetch` domain) and saves "Your name" while it is held. Before the fix the sync engine dropped pushed events from the outbox before the pull stored them, so the new circle looked empty for that moment and the name was refused with "A steward needs to let you in" (the intermittent `e2e.mjs` failure of November 2026). Uses ports 18092 and 9350.

## Service worker check

```
./gradlew :web:stampWorker
node tools/browser-e2e/sw-e2e.mjs
```

Serves a temporary copy of `web/build/dist/web` with a tiny static server (no Lendger server needed) and checks, in real Chrome: the worker registers and caches the shell; no update prompt after the first install; with the server stopped the page still opens; a changed version waits behind "Update now" while the old one keeps running; after the member agrees, the page reloads, only the new cache remains and the new files are served.

## Accessibility (axe-core)

With `A11Y=1` the script runs axe after the pages listed in `e2e.mjs` (welcome, start, join, sign in with and without an error, home, members, items with an item and with a locked one, add and change item, ask to borrow, borrow out and finished and in dispute, standing, stewards, settings, pairing, cut off). The first run found one real class of failure on six pages: touch targets smaller than the WCAG 2.2 minimum (2.5.8); `styles.css` now sets 44 pixel targets. Rules axe cannot judge (reading level, screen reader flow, keyboard order, colour in high-contrast modes) still need the manual pass in the spec.

