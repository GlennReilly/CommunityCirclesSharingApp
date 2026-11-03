# 0039. Device key storage in the browser

- Status: Proposed
- Date: 2026-11-03

## Context

Sign-in cannot be built until we know how a browser keeps its device identity. A device holds two secrets of 32 bytes each: the Ed25519 signing seed and the X25519 box secret (`DeviceIdentity`). Everything else a device knows (circle keys, item keys) is recovered from the log by unwrapping with the box secret, so it can be rebuilt and does not have to be stored.

Who we defend against:
- **Someone who gets at the browser profile** (a lost or shared laptop, a phone borrowed unlocked, a backup of the profile): they must not be able to act as the member or read the circle.
- **Malware or a malicious extension on the device**: we cannot stop it. Keys in memory are readable by it either way.
- **A script injected into the page**: the strict policy of ADR 0037 (no inline script, own origin only) is the defence; nothing here changes it.
- **The server operator**: never sees any of these secrets, whatever we choose.

Also relevant: the browser may clear site storage (ADR 0036), the spec's recovery code idea (spec 5, open decision 4), and the audited libraries we already ship (noble provides scrypt and Argon2id, and XChaCha20-Poly1305 for the wrapping).

## Options

**A. Plain bytes in IndexedDB.** Simplest. Anyone with the profile has the member's identity. Not acceptable for a trust-based community app.

**B. Passphrase.** The two secrets are encrypted (XChaCha20-Poly1305) under a key derived from a passphrase with a memory-hard function (Argon2id or scrypt, parameters stored with the blob). The member types it when the app opens. Works in every browser. Costs: members must remember it, a weak passphrase is guessable by someone holding the blob, and derivation takes a noticeable moment on a slow phone (tuned to roughly a second).

**C. Passkey (WebAuthn PRF extension).** The authenticator (phone, laptop, hardware key) returns a secret only after the member's fingerprint, face or PIN; that secret wraps the keys. Nothing to type or remember, and nothing guessable offline. Costs: PRF support varies by browser, operating system and authenticator and must be tested on the devices of the pilot group; a passkey that syncs through a platform account moves trust to that account; losing the authenticator means losing the wrapped keys (recovery then goes through the recovery code or a steward).

**D. Both (the spec's plan).** Use a passkey where the browser offers PRF, otherwise a passphrase; the member can add the other later. The blob records which wrapping it uses. More code and two paths to test, but nobody is locked out by their browser.

**Recovery code (separate but linked).** A 24-word code that regenerates the device identity lets a member restore after losing the device or the passkey. It requires the two secrets to be derived from one 32-byte seed (domain-separated hashing), instead of two independent random keys as `DeviceIdentity.generate` makes today. A new device is still registered by a `device.add` event; the code regenerates the same identity on a replacement browser.

## Proposed decision (for the owner to confirm or change)

1. **Option D**: passphrase always available, passkey (PRF) offered where the browser supports it. Sign-in asks for one at join time; the passphrase has a minimum length and a plain-language strength hint (no composition rules).
2. **Wrapping:** XChaCha20-Poly1305 under a key from Argon2id (parameters tuned to about one second on the pilot's slowest phone, stored with the blob) or from the PRF output (expanded with a labelled BLAKE2b). The blob stores a version, the method, the parameters, the nonce and the ciphertext; it is bound to the device id as associated data.
3. **Unlocked keys live in memory only**, in the worker that verifies and folds (ADR 0036), never written back in the clear, never logged. They are dropped on sign-out and after 15 idle minutes by default (member can choose "until I close the tab" or "never lock on this device" with a warning).
4. **Derive both device secrets from one seed** so a recovery code can restore the identity; the seed is what gets wrapped. This changes `DeviceIdentity.generate` and is the one place the existing shared code has to change.
5. **The recovery code is optional per member** and shown once, with the instruction to write it on paper. Its wordlist and exact format are the separate open decision 4 and are not fixed here.
6. **Document** that clearing site data deletes the wrapped keys, and that the log and blobs can be downloaded again but the keys cannot.

## Consequences (if accepted as proposed)

- A stolen unlocked browser profile is not enough to act as the member; the attacker needs the passphrase (and has to guess it offline against Argon2id) or the passkey.
- Members meet an unlock step each session. The idle lock is the trade between convenience and safety on shared devices.
- Passkey support must be tried on real devices before the pilot; the passphrase path means a failure there is not a blocker.
- Changing from random keys to a seed-derived identity needs tests (determinism on JVM and JS, a fixed vector from an independent implementation, as with the other crypto).
- Rotating the passphrase or adding a passkey later rewraps the same seed and does not touch the log.
