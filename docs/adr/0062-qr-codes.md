# 0062. QR codes

- Status: Proposed
- Date: 2026-11-04

## Context

The spec lists "QR verification" for members and "QR tag printing" for items. Today people check a member by reading a 20-character key fingerprint aloud, which is accurate but slow, and there is no way to put an item's name on the shelf. Two things bound the design: the script budget (about 7.5 KB gzipped left under 250 KB, ADR 0009), and the privacy rule that nothing leaves the circle's own origin. A QR code is only a picture of some text, so everything depends on what the text is and who reads it.

## Decision (proposed)

1. **A QR code always encodes a link into this app**, never a secret: `{origin}/#/verify/{circle}/{member}/{fingerprint}` for people, `{origin}/#/item/{item}` for items. A phone's own camera app reads it and opens the link in the browser, so there is no scanner inside Circles.
2. **Verifying a person.** Members shows a "Show my code" button on the member's own card. The page draws a QR code of the verify link and the fingerprint in words below it. When the other person opens the link signed in, the app finds that member in the circle, computes the fingerprint of their first device itself and says either "Matches: this is member X" or "Does not match" (and never trusts the number in the link). Opened signed out, it says to sign in first.
3. **The code is drawn on the device.** A small QR encoder in `shared` (byte mode, error correction M, versions 1 to 10, so up to 213 characters), tested against an independent implementation, drawn as an inline SVG; no library, no network.
4. **Item tags (if chosen).** An item's page can show a QR for `#/item/{id}` and a print view (one tag per item: name, QR, the circle's address) set up with print styles; opening an item link shows that item in the list.

## Decisions for the owner

**A. What gets a QR code. A1 (recommended): people only** (verification). **A2: people and item tags**, with a print view; about 1 KB more script and a bit more UI, useful for tools in a shed.

**B. Reading. B1 (recommended): the phone's own camera app**; nothing to build, no camera permission asked by Circles, and no 40 KB scanning library. **B2: a scanner inside the app** (camera access via the browser, a decoder library): it works on devices whose camera app does not read QR codes, but costs most of the remaining size budget and a permission prompt.

**C. Where the encoder comes from. C1 (recommended): our own small encoder with tests against an independent one** (about 3 KB gzipped). **C2: a published library** (`qrcode-generator` or similar, 5 to 15 KB gzipped), less code to own but it would use up the headroom.

## Consequences

- With A1, B1 and C1 the cost is about 3 KB of script and a screen section; it fits.
- A link in a QR code opens only where the app is served, so each circle's address appears in the code; a stranger who photographs it learns the server's address and a public fingerprint, nothing secret.
- Item tags make the item link public to anyone who can see the tag, which only helps members: the page still needs sign-in and membership.
