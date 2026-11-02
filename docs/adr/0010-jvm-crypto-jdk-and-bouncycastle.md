# 0010. JVM crypto for M1: JDK Ed25519 and BouncyCastle BLAKE2b

- Status: Accepted
- Date: 2026-11-02

## Context

Spec section 5 says "libsodium primitives only" and asks for a JVM binding to be chosen. In M1 the server (and the JVM test clients) only need to sign, verify and hash: Ed25519 and BLAKE2b-256. A native binding (lazysodium + JNA) needs a platform library, which breaks the single-jar, run-anywhere deployment (design doc, section 8).

## Decision

On the JVM, use the JDK's built-in Ed25519 (`java.security`, JDK 15+) and BouncyCastle's `Blake2bDigest` for BLAKE2b-256. Nothing is hand-written. The algorithms are the standard ones (RFC 8032 and RFC 7693) and give the same output as libsodium's `crypto_sign_detached` and `crypto_generichash` (32-byte output, no key); the tests include the RFC vectors to show it. The `Crypto` interface in `shared` hides the choice. The browser will use libsodium-wrappers, so the rule's intent (no custom cryptography) holds even though the literal "libsodium only" wording does not for the JVM.

## Consequences

- One jar, no native code. BouncyCastle (`bcprov`) is the only extra dependency, used for one digest.
- There is no JS `Crypto` implementation in M1 (Node has no BLAKE2b-256). Signature and id vectors run on the JVM only; canonical-byte vectors run on both targets. JS gets libsodium in M2/M3.
- Scope is M1 and the server, which never decrypts. M2 needs XChaCha20-Poly1305 and sealed boxes on the JVM test clients; BouncyCastle probably does not provide `crypto_box_seal`. Expect a new ADR then, probably lazysodium as a test-only dependency.
- JDK and libsodium may differ on edge cases (small-order public keys, non-canonical encodings). Ids and signatures are produced by honest clients, so this is only a validation-strictness difference; revisit if the browser and server disagree.
