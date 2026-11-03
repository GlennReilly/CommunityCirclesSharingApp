# 0042. Standing from loans and vouches, and the access check (M4, first slice)

- Status: Proposed
- Date: 2026-11-03

## Context

The policy library (`circles.policy`, corrections C1 to C13) already computes standing, tiers and access decisions, but nothing feeds it from the log: `CircleFold` takes a `mayBorrow` hook that allows everyone (ADR 0025), loans earn nothing, and there is no way to vouch. So today a newcomer can borrow anything and good lending changes nothing. M4 is large (vouching, confirmation, disputes, pauses, config updates); this ADR covers only the first slice: **standing from finished loans, vouching, the access check, and the My standing screen**. Disputes, `standing.confirm`, notes, pauses and config changes are later slices.

Two facts shape the design. Every client folds the same log and must reach the same answer, so anything that depends on "today" must use the day carried by the event instead. And a new circle has a bootstrap problem: nobody has points yet, VALUABLE items need NEIGHBOUR, and a voucher must be NEIGHBOUR or above, so nobody could ever lend or vouch for anything but essentials and everyday items.

## Decision (proposed)

1. **Standing is derived, never stored.** The fold builds `StandingEvent`s and `Vouch`es from the log and calls the existing `StandingCalculator.computeAll`. The calculator is unchanged.
2. **Which loans earn what** (a loan counts once it has a `loan.return` and `returnGraceDays` have passed since the return's day with no dispute; 7 days, a `CircleConfig`-style constant until config events exist; a loan whose hand-over both sides confirmed is the only kind that can be returned, so "no confirmed hand-over earns nothing" already holds):

   | Reported outcome | Borrower gets | Owner gets |
   |---|---|---|
   | OK, back by the agreed end day | `BORROWED_AND_RETURNED_OK` (+0.5) | `LENT_ITEM_RETURNED_OK` (+1.0) |
   | OK, but reported after the agreed end day | `RETURNED_LATE` (-0.5), judged against the window agreed in `loan.respond` | `LENT_ITEM_RETURNED_OK` (+1.0) |
   | LATE | `RETURNED_LATE` (-0.5) | `LENT_ITEM_RETURNED_OK` (+1.0) |
   | DAMAGED_REPORTED or LOST_REPORTED | nothing | nothing |

   Honest reporting never costs anything (spec 3.3). The counterpart is the other party, so the per-counterpart credit cap (C8) applies. The only large negative, `DAMAGED_OR_LOST_UNREPORTED`, needs `loan.dispute` and arrives with the dispute slice.
3. **`vouch` event.** Author: a member. Payload (sealed): `vouchee` (member id) and `expiresDay`. The public part is empty. A vouch is valid only if the vouchee is a member of the circle, is not the author, and the author's tier on the vouch's own day is at least `voucherMinTier` (NEIGHBOUR); other vouches are ignored and shown in the debug list. A voucher may vouch again to renew; repeats count once (C1). The calculator caps the expiry at 180 days.
4. **The access check is made in the fold, on the request's own day.** A `loan.request` is accepted only if `AccessPolicy.canBorrow` is `Eligible` for the borrower, using the standing computed from the events that come before it in the log, evaluated at the request's `day`. Every client therefore decides the same way, whatever day they open the app. A refused request has no effect and is listed as rejected with the policy's reasons. The screens check the same rule first, so a member is told "not yet, and how to unlock" instead of sending a request that will be ignored.
5. **Owner consent is unchanged.** Eligibility lets a member ask; the owner still approves or declines.
6. **Screens.**
   - *My standing*: your own tier, your own points rounded to a tenth, the plain-language reasons, and what unlocks more. Nobody sees another member's tier or points (spec 6).
   - *Items*: next to each item you do not own, either "You can ask to borrow this" with the button, or "Not yet" with the reasons and how to unlock.
   - *Members*: a "Vouch for this person" button shown only to members who may vouch, and who they already vouched for until when.
7. **Time.** Standing shown on screen is computed at today's date; standing used by the fold is computed at the event's day. They agree whenever nothing is dated after the event.

## Decisions for the owner (these change what the circle feels like)

**A. The bootstrap.** Options:
   - **A1 (recommended): stewards count as at least TRUSTED.** The role floor applies in the fold for tier decisions, so a founding steward can lend anything and vouch from day one, and a steward's vouch counts. Everyone else earns their tier. The cost is that stewards are trusted by role before they have a history; they are the people the circle already picked.
   - **A2: founding members start with starter points** (a one-time `STEWARD` grant at `circle.create`, decaying like any points). Fewer special cases, but points are meant to come from real activity and the grant is arbitrary.
   - **A3: no bootstrap.** Early on only essentials and everyday items can move, and tiers grow from those loans. Simple and strict, but VALUABLE and HIGH_VALUE items stay locked for weeks and nobody can vouch.

**B. Enforcement.** Options: **B1 (recommended)**: the fold ignores requests that fail the check, as in point 4. **B2**: the screens warn but the fold accepts everything, so a modified client can borrow anything; the only protection is the owner's consent.

**C. Reported damage or loss.** **C1 (recommended)**: nothing for either side, as in the table. **C2**: the owner still gets the small `LENT_ITEM_RETURNED_OK` credit because they lent in good faith. Either way the borrower is not penalised.

## Consequences

- Good lending and borrowing finally changes what a member can do, and a newcomer sees why and how to unlock more.
- Folds get heavier: standing is recomputed at each request's day. For circle sizes in the spec (hundreds of members, thousands of loans) that is acceptable; if it is not, the fold can cache per-member standing incrementally (the snapshot fold of ADR 0036 will need to anyway).
- A request refused by the fold is invisible to the requester after they sync, which is why the screens check first and the refused-changes notice explains it.
- The weights, grace days and thresholds are the library defaults until config events exist (later slice); changing them later changes everyone's tiers at once, so they should be settled in the pilot (spec open decision 6).
- Disputes, confirmation, contribution notes, pauses and per-circle config are not covered; until the dispute slice exists, unreported damage cannot be recorded.
