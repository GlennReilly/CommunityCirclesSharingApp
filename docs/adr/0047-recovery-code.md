# 0047. The recovery code

- Status: Accepted
- Date: 2026-11-03

## Context

A member who loses every device loses their keys. A steward can add a fresh identity for them, but a steward cannot give back what only the member held: the private details of the member's own items (their item keys were wrapped only to the member's devices) and of loans they were approved for. Spec 5 lists an optional recovery code, "a 24-word encoding of a seed that can regenerate the device identity", and leaves the wordlist and format open (open decision 4). ADR 0039 already derives a device's signing and box keys from one 32-byte seed, so a code can be a way to write that seed down.

Another device (ADR 0046) is registered in the log and receives wraps of every circle key epoch, every owned item's key and every approved loan's key, because those events wrap to all of a member's live devices. A device nobody ever runs, whose seed is kept only on paper, therefore keeps a complete, current copy of everything the member can read.

## Decision (proposed)

1. **A recovery code is a recovery device.** Creating one makes a fresh random seed and registers it as another device of the member (named "Recovery code") by the same steps as adding a device (ADR 0046): `device.add`, a wrap of every circle key epoch, an item key wrap for every owned item. The seed is shown once as words and kept nowhere on any device. From then on the ordinary rules keep it current: every rotation, new item and approved loan wraps to it like to any live device.
2. **The device id and the seed come from the words' entropy.** The code is 128 bits of random `entropy`. The device seed (ADR 0039) is `BLAKE2b-256("circles/recovery-seed/v1" ‖ entropy)` and the device id is the first 16 bytes of `BLAKE2b-256("circles/recovery-device-id/v1" ‖ entropy)`, so that a browser holding only the words can sign in without knowing anything the server has to tell it.
3. **The words.** The 128 bits plus a 4-bit checksum give 12 words of 11 bits from the BIP-39 English list (2048 words, public domain and chosen for words that differ in their first four letters). The checksum is the top four bits of the first byte of `BLAKE2b-256("circles/recovery-code/v1" ‖ entropy)`, not BIP-39's, so the words are not a valid wallet phrase anywhere else and cannot be mistaken for one. Entry ignores case and spaces, and a mistyped or unknown word is reported by position.
4. **The wordlist is a file** (`wordlist.txt`, served beside the page, cached by the service worker and covered by its version) loaded only when the member makes or enters a code, so it costs nothing in the size budget.
5. **Making the code** (Settings, "Recovery code"): the screen shows the 12 words numbered, with the restore link, and the instruction to write them on paper and keep them like a house key. Before the code is registered the member must type three of the words (asked by position, chosen at random) to show they have a copy (decision B). One code per member: making a new one removes the previous recovery device, as in ADR 0044, so stewards are asked to change the key.
6. **Restoring** is a link `{origin}/#/restore/{circle}/{member}` shown with the code (public ids only). Opened in any browser, the page asks for the 12 words and a new passphrase for that browser. It derives the recovery identity, signs in as the recovery device and syncs, and finds out whether the words really name a registered device of that member in that circle (if not, nothing is kept and the page says so). Everything the member could read is then there.
7. **Using a code uses it up** (decision D1, as built): the restoring browser makes a fresh ordinary device identity for itself, and with the recovery identity registers it (the steps of ADR 0046, a wrap of every epoch and every owned item's key) and removes the recovery device in the same batch (a steward member also changes the key at once, others leave the prompt of ADR 0044 for a steward). The browser then runs as an ordinary device protected by the new passphrase, and Home suggests making a new recovery code. So the paper that was used is no longer a key to anything, and nobody keeps running as the identity whose words are lying around.
8. **Honest limit.** Whoever holds the words can act as the member, exactly as with any device. Write them by hand, keep them somewhere safe, and replace them if they may have been seen. A circle cannot recover a member who has neither a device nor their words; a steward can then only add a fresh identity.

## Decisions for the owner (answered 2026-11-03: A2, B1, C1, D1)

**A. Length. Chosen: A2.** **A1: 24 words** (256-bit seed), as the spec says. **A2 (chosen): 12 words** (128-bit value expanded to a 256-bit seed, with a 4-bit checksum): half as much to write and type, and 128 bits is still far beyond guessing, though it departs from the spec's "24-word" (the spec line is amended).

**B. Proving the member wrote it down. Chosen: B1.** **B1 (recommended): ask for three words** by position before the code is registered. **B2: no check**; faster, but a code that was never copied is a false sense of safety.

**C. Wordlist and checksum. Chosen: C1.** **C1 (recommended): the BIP-39 English list with our own checksum**, so the words are never a valid wallet phrase. **C2: standard BIP-39** (words also valid in any wallet); interoperable, but invites pasting a recovery code into software that treats it as money.

**D. After a restore. Chosen: D1.** **D1 (recommended): the used code is retired** at once (point 7) and a new one is suggested, because the paper has just been used and may be lying about. **D2: do nothing**; the used code stays a key until the member replaces it.

## Consequences

- A member who loses everything can get back their account, their items' private details and the approved loans' details from a piece of paper.
- The recovery device is visible in the devices list and the member count of devices on the Members page: "Recovery code" is a device that is never online. A steward's "Change the key" prompt after replacing it is the cost of replacing.
- Making a code needs the existing device's keys, so it is impossible to make one after everything is lost; it is something to do early. The Home screen suggests it until it is done.
- The device id derivation, the 12-word format, the seed and device id derivations and the checksum get independent test vectors (BIP-39 wordlist and an independent encoder in Python), like the other formats.
- The recovery device holds a full copy of the member's readable data for as long as the code exists, which is the point and the risk.
