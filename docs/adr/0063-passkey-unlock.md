# 0063. Unlocking with a passkey

- Status: Accepted
- Date: 2026-11-04

## Context

ADR 0039 chose option D: a passphrase always, and a passkey (WebAuthn with the PRF extension) where the browser offers it. The passphrase and the saving of it by the browser's password manager (ADR 0056) came first; the owner also asked not to retype it each time. A passkey gives a secret only after the person proves themselves to the authenticator (fingerprint, face, PIN, security key), so nothing is typed and nothing guessable is stored.

## Decision

1. **Opt-in, per device, from Settings** ("Unlock with this device", only offered when the browser has WebAuthn). The member must already be unlocked, so the seed is in memory.
2. **Turning it on**: a credential is made on this device (`userVerification: required`, PRF requested); the authenticator is asked once for its secret with a fresh 32-byte salt; the device seed is sealed with XChaCha20-Poly1305 under `BLAKE2b-256("circles/passkey-wrap/v1" ‖ secret)`, with the device id and credential id as associated data. The record (credential id, salt, sealed seed) is stored beside the passphrase wrapping; it holds nothing that opens without the authenticator.
3. **Unlocking**: when a record exists, the sign-in page shows "Unlock with this device" first and the passphrase form below. A cancelled prompt, a failed fingerprint check or a different secret leaves the browser locked and says so kindly.
4. **The passphrase never stops working**, and changing it does not touch the passkey record. "Turn this off" removes the record. Forgetting the device removes everything.
5. **Where it works**: only where browsers allow WebAuthn: `localhost`, or a name over HTTPS. Not on a bare IP address, even with HTTPS (the browser refuses it); then the Settings message says to open Circles by a name. On plain `http://` with an address the browser has no WebAuthn, so the option is simply not shown.
6. After signing in, the page shown is the one the address bar names (a reload or bookmark), not always Home.

## Consequences

- A stolen unlocked profile is still not enough to act as the member without the passphrase or the authenticator. A passkey unlock needs the person present at the device.
- Authenticator support varies; the browser script uses Chrome's virtual authenticator with PRF. Real devices (a phone, a laptop with Windows Hello or Touch ID, a security key) must be tried in the user testing.
- The saved record ties the identity to that authenticator on that device. Moving to another device is a second device or the recovery code (ADRs 0046, 0047), not the passkey.
- The browser script now serves from `http://localhost:...` instead of `127.0.0.1`, because WebAuthn refuses IP addresses.
