# 0073. Removed items keep a reachable loan history; history pages can be searched

- Status: Accepted
- Date: 2026-11-12
- Supersedes: only the consequence of ADR 0072 that says retired items' owner history "is no longer reachable". The decision of ADR 0072 is unchanged and is not edited.

## Context

ADR 0072 added loan history for owners and borrowers. An owner who removes an item (`item.retire`) loses the Loan history button, because the Items page lists only items still on the list, so the record of who borrowed it and how it went could not be reached any more. Separately, the history lists grow with use and a member looking for one loan has to scroll.

## Decision

1. **Removed items section.** The owner's Items page ends with a **Removed items** section (shown only when there is something in it), listing every item the member owned that is no longer listed and that has at least one loan, most recent loan first, each with its loan count and a **Loan history** button. The button opens the same sub page as before (`#/items/history`, `AppState.historyOf`), which now also accepts a removed item: the heading is the item's name, and a line says "You removed this item from the list. Its loans are kept here."
2. **Derived, not stored.** `LoanHistory.removedOwnedItems` works out the removed items from the loans in `HomeData` (owner role, item not in `HomeData.items`). No new field, event, or server change. An item that never had a loan has no history to keep and is not listed.
3. **What the device can read.** The name is taken from the loans; it is the name the fold holds for the item. If this device cannot read it, the page says "An item you removed". The page never prints the item's private description, as in ADR 0072. Only the owner sees removed items; a borrower's history already shows "No longer on the list." for an item that is gone.
4. **Search.** Both history pages have a "Search: " box (shown when there is more than one loan). It filters on this device as you type, by every word typed (any case) against the item name, the person's display name, the dates and the outcome words (and a dispute's sentence). The count line ("N loans", a status region) follows it. On the borrowing history an item with no matching loan is hidden with them. It is the same mechanism as the Skills search (`filterRows` in `Main.kt`, an `ATTR_HISTORY_TEXT` attribute on each loan), which now also matches by all words instead of one phrase.

## Consequences

- Nothing is stored or sent; the section is as complete as the log this device holds.
- A removed item whose loans were never read (the device was behind) is shown by its loans only, so it cannot be listed until the loans have synced.
- Items removed by an owner who has since left the circle are not shown to anyone else; borrowers keep the "No longer on the list." line.
- A removed item cannot be put back; adding it again makes a new item with an empty history.
- The search changes only what is hidden in the page; reloading or any redraw (for example after a sync) clears it.
