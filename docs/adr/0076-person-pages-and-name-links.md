# 0076. Every member name is a link to a person page

- Status: Accepted
- Date: 2026-11-12
- Supersedes: nothing. It builds on ADR 0068 (display names), 0069 (unique names) and 0071 (checking identity in person); their wording and rules stand.

## Context

A member's name appears on many pages (Members, Items, Borrow, histories, Stewards, Pauses, Skills, the check pages). Until now it was plain text, so there was no way from a name to "who is this, what do they offer, what has passed between us". The owner decided that every place a name is shown should lead to one page about that member.

## Decision

1. **A person page.** `#/members/person/{member}` is a sub page (`Screen.Person`, `AppState.viewing`, `AppAction.ShowPerson`, address parsed by `PersonLink`). The state is forgotten when the page is left or the app locks or signs out. With no member chosen, or a member who has left, the page says "This member is not in the circle any more." with a link back to Members (opening the bare screen goes to Members).
2. **What it shows.** The member's name as the heading, with "(identity confirmed)" / "(identity not confirmed yet)" and, when confirmed, who confirmed it (linked names); "Steward of the circle." for stewards; their items that are on the list, with the same Ask to borrow / Not yet logic as the Items page (`itemAccess`, shared); their skill offers; and "Your history with this member": the loans between the viewer and them, most recent first, through `LoanHistory.withPerson` (only loans the viewer was a party to as owner or borrower; a steward's view of someone else's loan is not history). For a member who is you: a link to Settings for your name and devices, your own items and offers, and no history and no check button.
3. **What it never shows.** Their level, points or pauses; any loan the viewer was not part of; an item's private description.
4. **Actions.** The page has a **Check identity in person** button that opens the ADR 0071 check page, where the QR route, the no-camera characters, Confirm identity and Vouch stay, because those actions need the in-person check first. They are linked from the person page, not duplicated on it.
5. **One helper.** Every name a view prints goes through `personName` in `ShellView.kt`, which writes the name as a link (`a.person-name`) followed by the identity words. `showStatus = false` leaves the words off inside a sentence ("Waiting for Bea to confirm too."); `link = false` is for the member's own page heading. Sentences that held a name as plain text (who confirmed an identity, loan hand-over lines, steward proposals, pause lines, contribution notes, the check results) are now built from text and `personName` pieces.
6. **Plain text stays only where a link cannot live.** Notices and banners (they clear themselves; `Notices.kt`), a search attribute (not shown), and a menu option. `nameOf` is the plain-text path; any use outside those carries a `plain-name:` comment saying why. `PersonNameSourceScanTest` fails for any other use of `nameOf`, `names[...]`, `displayName` or `myName` in the web code outside `Notices.kt`. Lendger has no PDF output.
7. **Link text and size.** The name itself is the link text. The link has a 44 px tap area made with padding and an equal negative margin, so the text lines up as if it were not a link; the browser script measures it on every page.

## Consequences

- A name in a menu (choosing someone to pause or thank) is still plain text, because an option cannot hold a link.
- A name inside a notice is not a link and is gone after 8 seconds, by design.
- One new sub page and one new action; no events, no server change.
- Anything that adds a name to a page without `personName` fails the scan.
