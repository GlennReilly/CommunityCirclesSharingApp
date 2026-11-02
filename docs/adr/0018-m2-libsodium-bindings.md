# 0018. M2 libsodium bindings: lazysodium on JVM clients (test scope), libsodium-wrappers on JS

- Status: Accepted
- Date: 2026-11-02

## Context

M2 needs two primitives beyond ADR 0010: XChaCha20-Poly1305 (IETF) for payloads and detail keys, and `crypto_box_seal` for key wrapping, plus X25519 box keys. BouncyCastle has no `crypto_box_seal`. Sealed boxes are built from X25519, HSalsa20, XSalsa20-Poly1305 and a BLAKE2b nonce, and BouncyCastle does not expose HSalsa20 as a public primitive, so assembling it would be hand-rolled cryptography, which the project forbids. The server never decrypts: it stores ciphertext and checks signatures. Only clients (the browser, and JVM test clients) need these primitives. A native binding on the server would break the single-jar deployment (ADR 0010, 0017).

## Decision

1. Add the AEAD and box operations to the `Crypto` interface in `shared` (`aeadEncrypt`, `aeadDecrypt` with a random 24-byte nonce, `generateBoxKeyPair`, `sealBox`, `openSealedBox`). Decryption failure returns a sealed result, not an exception.
2. JVM: implement them with `lazysodium-java` (which bundles libsodium natives and uses JNA), as a **test-scope** dependency only, in a `LibsodiumCrypto` class under `shared/src/jvmTest` (and reused by server tests). The `JvmCrypto` in `jvmMain` keeps ADR 0010 (JDK Ed25519, BouncyCastle BLAKE2b) and its encryption methods are not provided: the production server jar contains no libsodium and no JNA.
3. JS: implement `Crypto` with `libsodium-wrappers` (npm, pinned version) in `jsMain`. This also gives JS Ed25519 and BLAKE2b-256, so the JVM-only signature and id vectors can run on both targets.
4. Cross-implementation vectors: ciphertexts and sealed boxes made by one implementation must open in the other. Test vectors for XChaCha20-Poly1305 come from the libsodium and IETF draft test data, not from the code under test.
5. Shamir secret sharing is a separate decision (ADR to follow); it does not use these bindings.

## Consequences

- The server jar stays small and native-free. A server that is compromised or subpoenaed still holds only ciphertext, which matches the design.
- JVM tests need the lazysodium natives for the CI platform (Linux x86-64 and arm64 are bundled). If a developer machine lacks a bundled native, JVM crypto tests fail to load and say so.
- If a future JVM client ships to users (for example a CLI or Android app), it will need lazysodium in main scope; that would be a new ADR.
- Two implementations (lazysodium and libsodium-wrappers) wrap the same C library, so they should agree, and the cross-vector tests prove it.
- M2 must retire the plaintext `v = 0` envelope (ADR 0014) and introduce `v = 1`.
