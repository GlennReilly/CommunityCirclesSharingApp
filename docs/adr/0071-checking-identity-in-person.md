# 0071. Checking identity in person: one check page, fingerprints only as the fallback

- Status: Accepted
- Date: 2026-11-06
- Supersedes: in ADR 0068, the wording ("confirm their name", "(confirmed)") and where the confirm form sits ("on the member's card ... next to their fingerprint"); in ADR 0068's joining note, letting someone in by the fingerprint on the waiting line; in ADR 0046, the place of the fingerprint comparison (decision 1, third step); in ADR 0062, the fingerprint text on the Members page

## Context

The owner asked two things:

- (a) "Why are we showing the key fingerprint in the app? It seems like that might be combined with the QR code screen, as an alternative if someone doesn't have a working camera."
- (b) "We should not be saying 'Nobody has confirmed your name yet', we should be saying 'Nobody has confirmed your identity yet'."

Today a 20-character key fingerprint is printed on every member card, on the waiting-to-be-let-in line, inside the confirm form, under a skill offer, in the overdue-loan reminder and as a live preview on the device-pairing form. Most of these are decoration: nobody compares anything there. The places where a comparison really protects someone are the ones where a person vouches for another person's key:

1. a steward letting a new device in (it is then given the circle key),
2. a member confirming another member's identity (ADR 0068),
3. vouching (it raises the other person's level),
4. adding one's own second device (ADR 0046: whoever swaps the pasted code gets the account).

What a confirmation says has not changed: "this key belongs to the person I met". The name is only what the key holder calls themselves, so "identity" is the honest word.

## Decision

**Wording only, for names.** People read "Confirm identity", "(identity confirmed)", "(identity not confirmed yet)", "Nobody has confirmed your identity yet", "Identity confirmed by Alice", "You confirmed their identity" and the tick box "I checked this person's identity in person". Event types (`member.name.confirm`), its `how` value (`FINGERPRINT_IN_PERSON`), the fold, `NameResult`, `MemberSummary.nameConfirmed...` and every other identifier keep their names: this is wording, not a format change.

**The fingerprint only on check screens.** It is removed from member cards, the waiting line, the confirm form, skill offers, the overdue reminder and the pairing form. It appears only where two people compare it, always beneath or beside the QR route, as "No camera? Read these characters out to each other":

- **Show my QR code** (own Members card, and the Circle home page of someone still waiting to be let in): the QR code of the verify link (ADR 0062), the link to copy, then the characters.
- **Check identity** (new page, `#/members/check`): the characters the checker's device holds for that person, with how to use the QR route instead.
- **The verify page** a scanned QR code opens (ADR 0062, now titled "Check identity"): on a mismatch it shows both sets of characters, as before.
- **Check the new device** (new page, `#/settings/check-device`) and the waiting page of the new device.

**Flows.**

- *Letting someone in (steward).* Members, "Waiting to be let in": each line shows the person's name, or "New member (waiting for a name)", and a **Check and let in** button. It opens Check identity for that device: with a camera, scan the QR code on the newcomer's Circle home page; without, read the characters out (they are the waiting device's own, not the member's first device's). The **Let them in** button appears only after ticking "I checked this person's identity in person". If the steward scans the newcomer's QR code instead, the verify page says "Matches" and offers the same let-in form for every waiting device of that member whose characters match the code. The old one-click "Let in" is gone: letting in now always needs the tick (it did not before).
- *Confirming identity.* A member card has a **Check identity** button (never the form itself). Check identity, or the verify page after a matching scan, shows the confirm form; its **Confirm identity** button appears only after the tick, as before.
- *Vouching.* The Vouch button moved from the card to the same two pages, so the check is always one glance away. Vouching has no tick (it never had one; it states that you know the person, which ADR 0042 leaves to judgement).
- *Adding a device.* Settings, Add another device: paste the code and a name, then **Next**. This opens Check the new device, which shows the characters for the pasted keys; **Add this device** appears only after ticking "These characters match the ones on the new device". The new device's waiting page shows the code to copy and the characters to compare. No QR code here: a phone camera would open the pairing code as a link, and the code with the origin can exceed what our encoder holds (213 bytes, ADR 0062); it stays a later option.

**Model change.** `WaitingDevice` carries `fingerprint`, computed from that device's own signing key, so letting in compares the right key even when the member already has another device.

## Consequences

- The fingerprint no longer clutters every screen; when it appears, the page says what to do with it.
- Every act that trusts another person's key passes a page with the QR route and the read-out fallback, and letting in, confirming and adding a device need the in-person tick. Letting someone in is one step longer than before (a page and a tick), on purpose.
- Members, Check identity, Check the new device and the verify page are separate pages: one topic each (UI rules).
- A check page opened from a stale state (the person left, was already let in) says so and offers nothing.
- The pages are in the browser script with axe, and the view tests cover each flow; the old card fingerprint, the inline confirm form and the one-click Let in are gone from the tests.
