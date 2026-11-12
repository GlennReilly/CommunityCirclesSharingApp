# 0075. An archived item can be restored

- Status: Accepted
- Date: 2026-11-12
- Supersedes: only the consequence of ADR 0074 that says there is no restore, and the consequence of ADR 0073 that says "a removed item cannot be put back". Nothing else in either is changed, and neither is edited.

## Context

ADR 0073 and 0074 made archiving final: an archived item could only be replaced by adding a new item, which loses its history and photos. The owner decided that an archived item can be put back.

## Decision

1. **A new sealed event, `item.restore`.** Payload `{item}`, empty `public` part, sealed like every item event (ADR 0025). Only the item's owner may send it, and only for an archived item. The fold refuses it otherwise: "unknown item", "only the owner may restore an item", "item is not archived". A refused restore changes nothing. The server accepts the type opaquely (it is in `SUPPORTED_TYPES` and the opaque set, like `item.retire`); it cannot read it.
2. **What restoring does.** The fold sets `retired = false` on the same item. Nothing else changes: same item id, same detail key and key wraps, same sealed details, same photos, same name and class, and every loan of the item stays as it was. After the restore the item takes new requests, changes and details again, exactly like any listed item.
3. **Keys.** No new key is made by the restore itself. The ADR 0049 rule (change the item key when a member who was approved under it has since left) runs on the owner's device after every sync for items that are on the list. Archived items are skipped by that rule, so an item archived while such a member left is covered the first time it is on the list again after the restore syncs.
4. **Loans.** Archiving never touched loans (an open loan can still be returned), so restoring does not either. A loan open at archive time is still open after the restore. A departed borrower's loan stays in history as before.
5. **Borrowers.** `HomeData.items` lists the item again, so the borrowing history shows "Ask to borrow again" (or the usual why-not text) instead of "No longer on the list.", and a request is accepted.
6. **Listing archived items.** The owner's Items page "Archived items" section now lists every archived item the member owns, including one nobody ever asked for (ADR 0073 listed only items with loans, derived from loans; an item with no loans could not be restored that way). It comes from the fold as `HomeData.archivedItems`. Each shows its loan count, a **Restore** button and, when it has loans, **Loan history**. The history page of an archived item also offers **Restore**.
7. **Wording.** After restoring the notice is "Restored. The item is back on your list." and the Items page shows it. The history page line is "You archived this item. Its loans are kept here. You can restore it to put it back on the list." and the Archive disclosure says "You can restore an archived item later." (ADR 0074's "cannot be restored" text is replaced.)
8. **No other path back.** Adding an item with the same name is still a new item with an empty history.

## Consequences

- One new event type (`item.restore`), no change to existing events, no server migration. Older clients fold it as an unknown market type and keep the item archived until they update.
- Archive and restore can be repeated.
- An archived item's key is not rotated while it is archived (ADR 0049 skips items that are not listed), so the rotation can be late by the length of the archive.
- The restore button is the owner's only; nobody else sees archived items.
