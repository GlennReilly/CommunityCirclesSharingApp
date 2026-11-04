# 0056. Showing the passphrase, and saving it

- Status: Accepted
- Date: 2026-11-04

## Context

The owner asked for a standard show/hide control on passphrase fields and for a way not to retype the passphrase each time. The passphrase is what protects the device's keys (ADR 0039): Circles stores only the seed wrapped under it. Saving the passphrase inside Circles, in IndexedDB or local storage, would put the key next to the lock and defeat that protection for anyone with access to the browser profile.

## Decision

1. Every passphrase field has a "Show" / "Hide" button (accessible name "Show what I typed" / "Hide what I typed", updated as it flips). The field is hidden again whenever the page re-renders.
2. Circles never stores the passphrase. Saving is left to the browser's password manager, which the user controls: each passphrase form carries an invisible, fixed username ("Circles on this device") and the correct `autocomplete` values (`new-password`, `current-password`), so the browser offers to save and to fill it. The hint text says so.
3. Not decided here, left for a later ADR if wanted: unlocking with a device passkey (WebAuthn PRF, already named in ADR 0039) or a "stay unlocked on this device" option that keeps a wrapped key under a hardware-backed credential.

## Consequences

- Saving is as safe as the user's browser profile and password manager, and is their choice per device.
- A shared computer must not save it; the hint tells people the passphrase protects their keys.
