# 0049. Item detail keys after a removal

- Status: Proposed
- Date: 2026-11-03

## Context

An item's private details are sealed with its own key (ADR 0025). The owner holds it, and each approved borrower is sent it in `loan.respond`. ADR 0044 rotates the circle key when someone is removed but leaves item keys alone, so a removed member still holds the keys of items they borrowed.

How much this matters: events written after the removal are also sealed with the new circle key, which the removed member never gets, and their devices can no longer pull. The item key only matters if they obtain later ciphertext and the new circle key as well. It cannot unread what they already saw. So the gain is a second layer for future edits, at the price of the owner's details becoming unreadable to other current borrowers unless they are sent the new key too.

## Decision (proposed)

When a member is removed or leaves, the owner's device changes the detail key of every item that member was ever approved to borrow: a new `detailKeyId`, a new key wrapped to the owner's devices (`item.upsert`) and the details sealed again (`item.detail`).

## Decisions for the owner

**A. Who else gets the new key.** **A1 (recommended): borrowers with a loan still open** (approved, not yet returned) are sent it in the same `item.upsert`; the fold accepts wraps to devices of members with such a loan. Their view of the details keeps working. **A2: owner's devices only**: simplest, but other borrowers lose the details until the owner approves them again.

**B. When it happens.** **B1 (recommended): automatically**, on the owner's next sync after the removal (like the recovery shares of ADR 0048). **B2: a button** per item, with a prompt on Items.

**C. Or do nothing (C1).** Record the gap as accepted, given the small gain above. Cheapest; no code.

## Consequences

- A1 changes a fold rule (wraps in `item.upsert` may also go to open borrowers), so the server and client must agree and old logs still fold.
- Owners who are offline leave the old key in force until they next open the app.
