# 0074. Items are "archived", not "removed" (wording only)

- Status: Accepted
- Date: 2026-11-12
- Supersedes: nothing. It changes words that ADR 0073 chose; ADR 0073 is not edited.

## Context

ADR 0073 added a "Removed items" section, a history-page line "You removed this item from the list" and a fallback name "An item you removed". The owner decided that "archived" describes this better: the item leaves everyone's list, but its record is kept. "Removed" also suggests the record is gone, and it clashes with removing a member, which is a different thing.

## Decision

1. For items, the UI says **archive** everywhere it said remove: the Items page section is **Archived items** (intro: "Items you took off the list. Their loan history is still here."); the per-item disclosure is **Archive** with the button **Archive this item**; the history-page line is "You archived this item. Its loans are kept here. An archived item cannot be restored; to lend it again, add it as a new item."; the fallback name is **An archived item**.
2. This is wording only. The event type (`item.retire`), `ItemState.retired`, action ids (`retire-item`), `LoanHistory.removedOwnedItems` and every sealed value keep their names. Wording about members, devices, stewards, photos and skill offers is unchanged ("Remove this person", "Remove this device", and so on).
3. There is no restore. An archived item cannot be brought back; lending it again means adding a new item. Restoring would need a new event and its own ADR.

## Consequences

- ADR 0073's "Removed items" section, page line and fallback name are now "Archived items", the line above and "An archived item". The rest of 0073 stands.
- Tests and the browser script check the new words; a test asserts the old button text is gone from the Items page.
- Code and ADR text that say "removed" or "retired" for items stay as history; only what people read changed.
