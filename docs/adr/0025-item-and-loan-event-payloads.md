# 0025. Item and loan event payloads and folding rules

- Status: Accepted
- Date: 2026-11-03

## Context

Spec 3.2 puts the whole payload of `item.*` and `loan.*` events in `encrypted` with an empty `public`, and 3.3 names the types and their authors, but no fields are defined. Spec 5 says each item has its own 256-bit detail key, `item.detail` is encrypted with it, and an approving `loan.respond` wraps it to each of the borrower's devices. The server cannot read any of this, so every rule here is enforced by clients while folding (spec 3.4). Standing, tiers and access (`AccessPolicy.canBorrow`), disputes, `standing.*` and pauses belong to M4 and are not part of this decision.

## Decision

All payloads are CBOR maps, strict (exact key sets, bounded text), inside the usual circle-key seal. Ids (`item`, `loan`, `detailKeyId`) are random 128-bit lowercase hex made by the author.

| Type | Author | Payload |
|---|---|---|
| `item.upsert` | owner | `item`, `name` (1 to 80 chars), `class` (`ESSENTIAL`, `EVERYDAY`, `VALUABLE`, `HIGH_VALUE`), optional `minTier` (never below the class default), `detailKeyId`, `keyWraps` (the item key sealed to each of the owner's devices) |
| `item.retire` | owner | `item` |
| `item.detail` | owner | `item`, `detailKeyId`, `sealed` (the detail map encrypted with the item key, associated data bound to circle and item, at most 16 KiB) |
| `loan.request` | borrower | `loan`, `item`, `fromDay`, `toDay`, optional `note` (at most 256 chars) |
| `loan.respond` | owner | `loan`, `approve`, `fromDay`, `toDay` (the agreed window), optional `note`, `keyWraps` (item key sealed to the borrower's devices; required when approving, absent when declining) |
| `loan.handover` | owner or borrower | `loan` |
| `loan.return` | borrower | `loan`, `outcome` (`OK`, `LATE`, `DAMAGED_REPORTED`, `LOST_REPORTED`) |

Folding rules, applied in log order to events whose payload the device can open:

1. **Ownership.** The first `item.upsert` for an item id makes its author the owner. Only the owner may later upsert, retire or attach detail. A retired item takes no more events except loan returns for loans already active.
2. **Detail key.** The item key is not in any event in the clear. Owners wrap it to their own devices in `item.upsert`; borrowers get it from an approving `loan.respond`. The owner changes `detailKeyId` (with new `keyWraps`) to cut off future access; earlier approved borrowers keep the old key and old detail only. `item.detail` must name the item's current `detailKeyId`.
3. **Requests.** A borrower may not borrow their own item (C3). `fromDay` is not after `toDay`, the window is at most 90 days, and the item is not retired. A loan id is used once.
4. **Responses.** Only the owner answers, once, while the loan is requested. Approval needs `keyWraps` to devices that belong to the borrower; the agreed window is at most 90 days. The owner may send a second approval for an approved or active loan only to add `keyWraps` for the borrower's newer devices (the window and id must match).
5. **Hand-over.** Real only when both the owner and the borrower have each sent `loan.handover` for an approved loan; the loan is then active.
6. **Return.** Only the borrower, only for an active loan, once.
7. An event that breaks a rule is rejected with a reason in the debug list and has no effect. An event that cannot be opened (no circle key) has no effect either and is not a rejection.
8. **Hooks for M4.** The fold takes a `mayBorrow(member, item)` check, defaulting to allow everyone, where the access policy (tier, pause, cooldown) will plug in. Until then access is not enforced.
9. **Server.** The server accepts these seven types from any registered device of the circle, requires an empty `public` and a non-empty sealed payload, and applies the generic epoch and size rules. It does not and cannot check the fields above.

## Consequences

- A malicious member can send events that break these rules, but honest clients ignore them; the server only filters spam.
- A borrower whose new device appears after approval needs the owner to send the extra approval; otherwise that device cannot read the detail.
- Item detail keys are not recoverable from the log by anyone who lost every device the owner wrapped them to. Owners should keep two devices or accept that loss.
- Outcomes are recorded but earn no standing until M4 adds `standing.confirm`, the grace period and disputes.
- Changing a field name or rule needs a new ADR and migrated test data.
