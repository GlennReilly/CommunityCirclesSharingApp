# 0035. Browser cryptography with the noble libraries

- Status: Accepted
- Date: 2026-11-03

## Context

ADR 0018 chose `libsodium-wrappers` for the browser. The M3 bundle spike (ADR 0009) measured it at 245 KB gzipped, because the package embeds its WebAssembly as base64 text, against a whole-app budget of 250 KB; our own shared code is 77 KB and a UI layer a few KB more. The browser needs exactly five things: Ed25519, X25519, BLAKE2b-256, XChaCha20-Poly1305 and `crypto_box_seal`. The pure-JavaScript `@noble/curves`, `@noble/hashes` and `@noble/ciphers` provide every primitive for about 22 KB gzipped. In a Node check, they opened the sealed box and the XChaCha20 fixture made by PyNaCl (libsodium), and reproduced the RFC 8032 and BLAKE2b vectors.

## Decision

1. **The browser uses noble.** `JsCrypto` and `JsCipher` (Kotlin/JS) are implemented with `@noble/curves` (Ed25519, X25519), `@noble/hashes` (BLAKE2b) and `@noble/ciphers` (XChaCha20-Poly1305, HSalsa20, XSalsa20-Poly1305), pinned to exact versions. `libsodium-wrappers` leaves the shipped code.
2. **No primitive is written by hand.** The one composition we write is `crypto_box_seal`, exactly as libsodium defines it: an ephemeral X25519 key pair; the shared secret hashed through HSalsa20 with a zero nonce to a 32-byte key; the nonce is BLAKE2b with a 24-byte output of the ephemeral public key followed by the recipient's public key; the message is sealed with XSalsa20-Poly1305; the result is the ephemeral public key followed by the sealed message. About fifteen lines, covered by the tests below.
3. **Strict Ed25519 verification.** Verification is called with `zip215: false` so non-canonical encodings are refused, as RFC 8032 and libsodium do.
4. **libsodium stays as a test oracle.** `libsodium-wrappers` remains a dependency of the JS tests only. They check, with random and edge-case inputs (empty, one byte, block-size boundaries, large), that noble and libsodium agree on hashes, signatures, XChaCha20-Poly1305 and sealed boxes in both directions, and that tampering fails in both. The existing `Crypto` and `Cipher` contract tests, the PyNaCl fixtures and the JVM lazysodium implementation continue to apply unchanged.
5. **Randomness** comes from the platform (`crypto.getRandomValues`, through noble). A missing secure random source is an error, never a fallback.
6. **The rule is amended, not dropped.** "No custom cryptography: libsodium primitives only" now reads: libsodium primitives, except the JVM's JDK and BouncyCastle (ADR 0010) and the browser's noble libraries (this ADR), which implement the same standard algorithms and are checked against libsodium by the tests above.

## Consequences

- The browser bundle falls by about 223 KB gzipped, which brings the app under the 250 KB budget with room for the UI.
- Three npm dependencies take the place of one, all by the same maintainer. Before the pilot the audit reports they cite must be read and their dates recorded here; this ADR does not vouch for them.
- Pure JavaScript cannot promise the constant-time behaviour of libsodium's compiled code. The risk is a timing attack by something that can already run code or measure timing on the member's own device; that sits outside the threat model this project defends (the server operator and the network), but it is weaker than before and is stated here.
- If the choice proves wrong, swapping back is a change to two classes: the contract tests and fixtures decide whether any replacement is correct.
- Amends ADR 0018 points 3 and 5 (the JS implementation) and the cryptography rule in the spec and project instructions. ADR 0018's JVM decisions stand.
