# 0072. Loan history for owners and borrowers, with "Borrow again"

- Status: Accepted
- Date: 2026-11-12
- Supersedes: nothing (it adds two read-only pages; ADR 0025 and 0043 are unchanged)

## Context

The Borrow page lists a member's current loans; ended ones sit in a "Finished" section with no way to see all the loans of one item, and no shortcut to ask again for something borrowed before. Owners want to see who has had their item and how it went; borrowers want to find what they borrowed before. Loan events are sealed, so the server can see none of this: it is all folded on the device, and no server or wire change is needed.

## Decision

1. **Owner history.** On the Items page, every item the member owns has a **Loan history** button. It opens a sub page (`#/items/history`, state `AppState.historyOf`) listing every loan of that item, most recent first: the borrower (display name as everywhere else), the agreed dates, the day it was reported back when that differs, and the outcome: asked, approved, out now, returned on time, returned late, declined, cancelled, damage reported, loss reported. A dispute over a return is added as a plain sentence (pending review, upheld, dismissed). Damage and loss are stated as facts with "no penalty for telling", as on the Borrow page (spec section 6).
2. **Borrower history.** The Borrow page links to **My borrowing history** (`#/borrow/history`). It lists the items the member has borrowed before (a loan that ended: returned, declined or cancelled), grouped by item, the item with the most recent loan first, with the owner and each loan's dates and outcome. Loans still under way stay on the Borrow page.
3. **Borrow again.** Each item shows an **Ask to borrow again** button that opens the existing request form (`AppAction.BorrowItem`, `#/borrow/request`). It appears only when the item is still listed (not removed, owner still in the circle) and the member's access rules allow a request (`ItemSummary.access`). When the rules say not yet, the page shows the same "why, and how to unlock" disclosure as the Items page; when the item is gone it says "No longer on the list". There is never a button that cannot work.
4. **Privacy.** A member sees only loans they were party to, as owner of the item or as its borrower. Stewards get no extra view (a steward's dispute-resolving view of someone else's loan is not history). The pages never print an item's private description; where a device cannot read it, nothing changes.
5. **Where the logic lives.** `circles.app.LoanHistory` (shared, tested on JVM and JS) turns the `LoanSummary` and `ItemSummary` lists of `HomeData` into entries; `LoanSummary.returnedDay` is the one new field. The web module only draws it, with every tier word still going through `TierText` (the pages use none).

## Consequences

- Nothing is stored or sent; the history is as complete as the log this device holds, so a device that is behind shows less until it syncs.
- A loan counts as late when the borrower said so or reported it back after the agreed last day. Old loans with no return day (none exist in practice) count as on time.
- Retired items disappear from the Items page, so their owner history is no longer reachable. Reachable-after-removal can be added later without a format change.
- Two sub pages are added to `Screen.subPages`; no new events, no server change.
