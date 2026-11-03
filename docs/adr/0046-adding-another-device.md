# 0046. Adding another device

- Status: Accepted
- Date: 2026-11-03

## Context

A member has one browser today. If it is lost, broken or cleared, the member's keys, items and standing can only be recovered by a steward adding a new identity from scratch; and nobody can use the app on a phone and a laptop. The pieces exist: `device.add` (a member's existing device registers another device of the same member, with an optional sealed label), `key.wrap` (any member holding a key may wrap it to a registered device, ADR 0021), `device.revoke` and the key rotation of ADR 0044. What is missing is how the new browser and the existing one exchange what they must, safely, and the screens around it.

The new device must send its public keys to the existing one, and the existing one must be sure the keys it registers really come from the member's new device, not from someone who intercepted the message. Nothing secret has to travel between the two: the new device makes its own keys, and the existing one wraps the circle keys to the new device's public key.

## Decision (proposed)

1. **Three steps, no secrets exchanged.**
   - *Existing device, Settings, "Add another device":* shows a link `{origin}/#/device/{circle}/{member}`. It holds only public ids. It is opened in the new browser.
   - *New browser:* shows the page "Add this device to your account". The member chooses a passphrase for that device (as for any device). The browser makes a new device identity for that member, remembers the circle, and shows a **device code** (device id, signing key and box key, all public) and the same **fingerprint** the Members page uses (20 hex characters in groups of four). It then waits and checks every few seconds whether the existing device has added it.
   - *Existing device, Settings:* the member pastes the device code, a name for the device ("Phone"), and sees the fingerprint of the pasted keys. After the member has compared it with the one on the new device's screen, "Add this device" sends a `device.add` (name sealed), and one `key.wrap` for every circle key epoch this device holds.
2. **Why the fingerprint check.** Anyone who can swap the pasted code for their own keys gets a full copy of the member's access. Comparing the fingerprint on both screens catches that. Twenty hex characters (80 bits) cannot be matched by trying keys, unlike a short number.
3. **The member's own items** are re-wrapped to the new device automatically: for each item the member owns and has not retired, the existing device sends an `item.upsert` with the same name, class and detail key and a wrap of the item key for the new device only (the fold merges the wraps). So the new device reads the member's own item details at once.
4. **The devices list.** Settings lists the member's devices with their names ("This device" marked) and lets a member remove any of their *other* devices ("Remove this device") behind a disclosure.
5. **Revoking a device and the key.** Revoking a device that held the circle key calls for a rotation, as in ADR 0044. A steward who removes a device rotates the key in the same action; a member who is not a steward revokes, and every steward then sees the same "Change the key" prompt on the Members page. (The prompt now appears after any revocation that no rotation has followed, not only after a whole member leaves.)
6. **Limits.** A borrower's new device cannot read the details of items they are already approved to borrow until the owner approves again; stewards can add a device for another member (existing rule); a new device sees the whole history it has keys for, which is every epoch the existing device holds.

## Decisions for the owner (answered 2026-11-03: A1, B1, C1)

**A. How the member confirms the new device. Chosen: A1.** **A1 (recommended): compare the fingerprint on both screens**, as in decision 2. **A2: no comparison**; the member just pastes and adds. Faster, but whoever can alter the pasted text gets the account. A QR code to scan instead of reading would be a later convenience.

**B. A steward revoking a device. Chosen: B1.** **B1 (recommended): rotate at once** in the same batch, as in decision 5. **B2: never automatic**; the prompt appears for everyone including the steward who revoked, and a second click rotates. One more step, no surprise bulk of wraps.

**C. The member's own items on the new device. Chosen: C1.** **C1 (recommended): re-wrap automatically** (decision 3), one event per owned item. **C2: not automatic**; the new device can see the items but not their private details until the owner edits each item. Fewer events, but a confusing half-working device.

## Details settled while building

- The fingerprint is the 20-character code of the Members page, now one shared function (`Fingerprint`).
- A device waiting to be added is marked in its stored identity (`pairing`), not guessed from the log: a member who joined by invite also has no registration in the log until their join is accepted, and must not be mistaken for a pairing device. A waiting device checks every 15 seconds, no faster, because the server limits logins.
- The device code is `device.signKey.boxKey` (base64url) and the link `{origin}/#/device/{circle}/{member}`; both parsers are strict.
- The "Change the key" prompt of ADR 0044 now appears after any revoked device that no rotation has followed, not only after a whole member left.
- The new device's name is sealed in the `device.add` and read back from it.

## Consequences

- A member can use two or more devices and recover from a lost one: add a new device from a remaining one, remove the old one.
- The only way to recover when no device remains is still a steward adding a fresh identity and the member starting again (standing and items stay with the old member id); recovery codes remain a separate piece of work.
- Adding a device costs one wrap per epoch plus one event per owned item, all small.
- The device code and the link contain no secrets; the passphrase never leaves the device that chose it.
- The new device's first login fails until the existing device has added it (a normal 401); the page says "waiting for your other device".
