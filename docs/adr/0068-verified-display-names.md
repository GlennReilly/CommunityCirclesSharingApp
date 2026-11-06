# 0068. Display names: chosen by the member, confirmed by another

- Status: Accepted
- Date: 2026-11-06

## Context

Every screen named people as "Member 2b69a182": a slice of a random id. Nobody can tell who that is, so lending, vouching, pauses and disputes were hard to follow. The spec has always listed `member.profile` ("display name, contact preferences, coarse area"), but it was never built and the server refused it.

A name typed by its owner proves nothing: anyone can call themselves "Alice". What makes a member really that person is their key, checked in person by its fingerprint (ADR 0046, 0062). The owner decided: names are **self-declared and confirmed by a member** who checked the person in person.

Constraints: names are personal data, so they must be sealed (ADR 0004: a field is public only if the server must read it, and the server never needs a name). Every client folds the same log, so the rules must give the same answer on the JVM and in the browser.

## Decision

**Events.** Two sealed event types, both with an empty `public` part; the server accepts them like the other sealed types (sender checks only):

- `member.profile {name}`: the author sets or changes their own name. Only the name is built now; contact preferences and area are left for later.
- `member.name.confirm {member, profile, name, how}`: the author confirms `member`'s name. `profile` is the event id of the `member.profile` that set the name, `name` repeats its text, and `how` is the confirmer's statement, today only `FINGERPRINT_IN_PERSON`. The type keeps the `member.*` namespace of the event it confirms; it is the first three-part type name, which the wire format allows.

**Name rules** (`DisplayNames.problem`, the same on every client because it looks only at code points and fixed lists, never at the platform's Unicode tables):

1. Valid text (no lone surrogate), 1 to 40 code points (`MAX_DISPLAY_NAME_CODE_POINTS`; an emoji counts as one).
2. No control characters (U+0000 to U+001F, U+007F to U+009F).
3. None of a fixed list of invisible or look-alike characters: zero-width spaces and joiners, direction marks, embeddings, overrides and isolates, the soft hyphen, word joiners and invisible operators, the Hangul fillers, the BOM, specials (U+FFF0 to U+FFFF), and every space other than U+0020.
4. No space at either end, no two spaces in a row.
5. Not id-like: not only digits, and not only hex digits (ignoring spaces) when there are 8 or more of them (`ID_LIKE_HEX_DIGITS`), so "2b69 a182" is refused while short hex-letter names such as "Bea", "Ada" or "Dee" are fine.

Before saving, the app tidies what was typed: Unicode NFKC (from the platform, `circles.text.nfkc`: JDK `Normalizer`, browser `String.normalize`), every whitespace character turned into a plain space, runs of spaces made one, and the ends trimmed. The fold does not normalise, because two platforms may know different Unicode versions; it only applies the rules above, so a name stored by one client is judged the same by all.

**Fold** (`MarketFold`, results in `MarketView.names` as `NameState`):

- A `member.profile` sets the author's name and starts it with no confirmations; setting the same text again is refused ("that is already the member's name"). Changing back to an earlier name is a new name: its old confirmations do not return, because they name the old event.
- A `member.name.confirm` is refused if the author confirms themselves, the member has no name, the `profile` or `name` is not the member's current one (stale), the author already confirmed this name, or the author is neither a steward (at that moment) nor at tier REGULAR or above on that day.
- A confirmation counts only while the name is unchanged (any change starts a new `NameState`) and while the confirmer is still a member: confirmers who have left are filtered out when the view is read (`MarketView.nameConfirmers`), like vouches (ADR 0044).
- A name shows as confirmed once `NAME_CONFIRMATIONS_NEEDED` (1) current confirmations stand. Making this a circle setting is a later option (it would join `config.update`, ADR 0051); it is a named constant until then.
- Two members may have the same name. The key fingerprint stays on show as the real check.

**App and pages.** `CircleApp.setMyName` and `confirmName` (results `NameResult`), `HomeData.myName` and `names`, `MemberSummary.displayName/nameConfirmedBy/iConfirmed/canConfirmName`. A member who can read the circle but has no name is sent to one page, "Your name", before any other (after starting a circle, after being let in, and after unlocking an identity that has none). Settings has "Your name" to change it, warning that a change clears confirmations. Everywhere a member appears (Members, Borrow, Items, Standing, Pauses, Stewards, Skills, contribution notes, vouch and pause lists, the QR check page), the name is shown with "(confirmed)" or "(not confirmed yet)" in words; a member with no name yet reads "New member (waiting for a name)". The confirm form sits on the member's card (and on the page a QR code opens) next to their fingerprint, and its button appears only after the box "I checked this person's key fingerprint in person" is ticked.

**Joining (the design conflict).** The brief asked that the joiner's name travel with the join, so the steward letting them in sees it. It cannot without breaking a rule: a joiner has no circle key until a steward wraps one to them, so they cannot seal anything, and putting the name in the join's public part would show it to the server (ADR 0004). Sealing it under a key from the invite secret would be a new cryptographic composition. **Chosen: the safest option.** The name is set right after the joiner is let in: the first page they see once they can read is "Your name". While waiting, the steward sees "New member (waiting for a name)" and the person's key fingerprint, which is the check that matters at the door: compare it with the fingerprint on the joiner's screen before pressing "Let in". If a waiting member already has a name (a later device of a named member), it is shown.

## Consequences

- People see names instead of id slices, and can tell a name someone checked from one nobody has.
- The server learns nothing new: both events are sealed, the server only sees that they exist.
- A steward letting someone in still identifies them by fingerprint, not name; this is now said on the page.
- `member.profile` is no longer the server's example of an unsupported type; `SyncServiceTest` uses `member.nickname` (not declared) instead.
- Names are kept for members who left, so old loans and notes still read well.
- Later options: a circle setting for the number of confirmations; contact preferences and area in `member.profile`; marking two current members who share a name.
