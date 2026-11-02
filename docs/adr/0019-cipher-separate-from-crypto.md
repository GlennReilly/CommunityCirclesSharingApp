# 0019. Encryption lives in a separate `Cipher` interface

- Status: Accepted
- Date: 2026-11-02

## Context

ADR 0018 said to add the AEAD and sealed-box operations to the `Crypto` interface. `Crypto` is used by the server (`EventRules`, `AuthService`, `SyncService`), which only signs, verifies and hashes. Putting encryption on the same interface would force `JvmCrypto` to stub methods that the server must never have, or pull libsodium into the server jar.

## Decision

Encryption is a separate interface, `circles.crypto.Cipher`, in `shared` common code (XChaCha20-Poly1305 `encrypt`/`decrypt`, `generateBoxKeyPair`, `sealBox`, `openSealedBox`). `Crypto` is unchanged. Clients take both. Everything else in ADR 0018 stands (lazysodium in JVM test scope, libsodium-wrappers on JS).

## Consequences

- The server depends only on `Crypto`, so the compiler stops it from decrypting; the shadow jar was checked to contain no libsodium or JNA.
- Clients and test helpers that need both pass two objects (or a small holder type).
- Amends ADR 0018 point 1 only; 0018 stays accepted.
