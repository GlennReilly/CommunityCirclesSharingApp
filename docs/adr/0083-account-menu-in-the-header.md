# 0083. My level and Settings move into an account menu in the header

- Status: Accepted
- Date: 2026-11-13
- Supersedes: nothing. ADR 0057 describes the page frame (a header bar and a navigation bar) but fixes no list of pages, so only the frame's description is touched in spirit; it is not edited.

## Context

"My standing" (now named "My level", point 8) sat in the navigation bar beside the circle's pages, although it is about the member, and "Settings" is the member's too. The owner asked for a user details menu.

## Decision

1. **A menu in the header.** The header shows a button with the signed-in member's display name ("Your account" until a name is chosen) and, under it, a list with exactly two links: **My level** and **Settings**. Sync now and Lock now stay as the header icon buttons. The navigation bar keeps six pages: Circle home, Items, Skills, Borrow, Members, Stewards.
2. **A plain disclosure, not an ARIA menu.** The button has `aria-expanded` and `aria-controls`; the list is real links. A closed list is `hidden`, so its links are not in the tab order and the button is one stop. There is no `role="menu"` because arrow-key menu behaviour is not implemented.
3. **How it closes.** Escape closes it and puts focus back on the button; so does a click outside it. Choosing a link closes it and goes to the page, as any navigation does (focus then goes to the page's main area). Locking or signing out closes it; a redraw after a sync keeps it open.
4. **Current page.** On My level or Settings the button and the matching link carry `aria-current="page"` (the button also shows an underline); nothing in the navigation bar is current then.
5. **Size and layout.** The button and the links are at least 44 px high. The button text is clipped with an ellipsis beyond about 11 rem; the list opens in the header itself, on its own full-width row under the button, so it never covers the navigation bar (a floating list did at 375 px and at the largest text size, and axe flagged the covered links). It works at 375 px, in dark mode and at the largest text size (the header wraps).
6. **The name is plain text here.** The button text is a label for the menu, not a link to the member's page (ADR 0076), so the line that prints it carries a `plain-name:` comment and `PersonNameSourceScanTest` is unchanged.
7. **Renamed page (wording only).** The page called "My standing" is now **My level**: the menu link, the page heading and title, `aria-current`, the document title and every sentence that named it ("Your standing appears once...", "It counts towards your level", "Your points:", "Your level is local to this circle", "nobody is ever banned for a low level"). It opens with one plain line: "Your points and the vouches you have decide what you can borrow. This page shows your level and how to reach the next one." Tier words still come from `TierText`. A scan (`StandaloneWordsSourceScanTest`) fails if a UI string says "My standing" or "your standing". Identifiers (`Screen.Standing`, `StandingSummary`, `standing.*` event types), the route and sealed values are unchanged; no earlier ADR fixed the page's name, so nothing is superseded.
8. **Nothing else changes.** Routes (`#/standing`, `#/settings`), `Screen` ids and the other page titles stay. The first-name page (ADR 0068) still has no navigation and no menu.

## Consequences

- `Screen.accountMenu` lists the two pages and `Screen.navigation` excludes them; tests that count navigation pages account for it.
- The menu's open state lives in `AppState.accountMenuOpen`, so a redraw cannot close it by accident.
- Links to My level from other pages use the page's own title and still read sensibly.
- Any later page for the member's own use goes in `Screen.accountMenu`.
