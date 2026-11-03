# 0009. Web UI stack

- Status: Accepted
- Date: 2026-11-02

## Context

The spec proposes a Vite + TypeScript + Preact PWA consuming the `shared` module through a narrow Kotlin/JS facade, for bundle size and accessibility control. The project default is Kotlin for all suitable work, and a TypeScript UI adds a second toolchain and a Gradle/Vite integration to maintain.

## Spike results (2026-11-03)

The decision above asked for a spike before M3. I built a throwaway Kotlin/JS module on a branch that depends on `shared` and exports a facade using what a client needs (the event fold, signed admin requests, blob sealing, the image policy, JSON), and measured production bundles with Gradle's webpack task (minified and dead-code-eliminated). The libraries and Preact were bundled with esbuild. Sizes are of the files before and after `gzip -9`.

| Piece | Raw | Gzipped |
|---|---|---|
| `shared` Kotlin/JS (fold, serialization, coroutines, stdlib, admin auth, blob sealing, image policy), crypto stubbed out | 251 KB | **77 KB** |
| the same plus a small `kotlinx-html` page (heading, list, link, button) | 258 KB | 79 KB (the UI layer adds about 2 KB for a demo; it grows with the app) |
| `libsodium-wrappers` as used today by `JsCrypto`/`JsCipher` | 760 KB | **245 KB** (231 KB for the embedded WebAssembly, 13 KB for the wrapper) |
| everything above with real libsodium | 982 KB | **318 KB** |
| `@noble/curves` (Ed25519, X25519), `@noble/hashes` (BLAKE2b), `@noble/ciphers` (XChaCha20-Poly1305, HSalsa20, XSalsa20-Poly1305) | 56 KB | **22 KB** |
| Preact with hooks | 14 KB | 6 KB |

What it says:
- **The 250 KB budget is broken by libsodium alone, not by our code or the UI.** With libsodium the shell is about 318 KB before any screens; without it, the Kotlin code plus a UI layer is about 85 to 110 KB.
- **The UI choice is not a size question.** Preact (6 KB) and `kotlinx-html` (a few KB, growing with use) are both small. It is a question of toolchain: Kotlin only (one language, Gradle only, the project default) against TypeScript and Preact (a second toolchain with Vite and npm, and a tighter fit for diffing, focus handling and accessibility work).
- **The crypto choice is what needs deciding.** The browser needs Ed25519, X25519, BLAKE2b-256, XChaCha20-Poly1305 and `crypto_box_seal`. libsodium has all of them but costs 231 KB. The audited pure-JavaScript libraries above have each primitive for about 22 KB, and a sealed box is X25519, then HSalsa20 for the key, then XSalsa20-Poly1305, with a BLAKE2b nonce. Using them in the browser would amend the rule "libsodium primitives only" (ADR 0018 and the project rules), and would need interoperability tests against libsodium (the existing `Crypto`/`Cipher` contract tests and the PyNaCl fixtures already provide them).
- **Fallbacks that keep libsodium:** load it lazily after the shell is drawn (the shell is then about 100 KB but the first full load is still over 300 KB; the service worker caches it afterwards), or build libsodium with the WebAssembly served as a separate binary file (about a third smaller, but a custom build to maintain).

Side finding: with three Kotlin modules Gradle loads the Kotlin plugin once per module and the Kotlin/JS tooling clashes. Declaring the plugins once in the root build file (done) fixes it.

## Decision

**The web client is written in Kotlin/JS with `kotlinx-html`**, in a Gradle module that depends on `shared` directly. There is no TypeScript, Vite or npm application of our own. The spec's proposal (Vite, TypeScript, Preact) is not adopted.

- The measurements above show the choice is not about size: Preact adds 6 KB and `kotlinx-html` a few KB, both far inside the budget once the crypto is settled (ADR 0035).
- One language and one toolchain keep the whole project, tests and build in Gradle, and let the client call the fold, the policy, the export verifier and the signing helpers as plain Kotlin, with no facade to keep narrow.
- Components are small functions that build DOM with `kotlinx-html` and re-render a subtree from a state value. Focus, live regions and keyboard handling are written deliberately and tested; the accessibility pass in M6 (axe in CI, screen reader checks) is the check on that choice. If it proves too costly, the rendering layer can be replaced without touching the logic, because the logic never depends on it.
- Compose Multiplatform for Web is not used: its output is far larger than this budget allows.

## Consequences

The `web` module becomes a Kotlin/JS Gradle module (M3). Rendering is hand-written, so the client must keep view code separate from state and sync logic so tests exercise the logic without a browser. The bundle budget (250 KB gzipped for the shell) is checked in CI once the module exists.
