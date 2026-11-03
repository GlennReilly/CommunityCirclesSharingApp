# 0043. Owner confirmation, disputes and their resolution (M4, second slice)

- Status: Proposed
- Date: 2026-11-03

## Context

ADR 0042 made returned loans earn standing after a grace period of 7 days. Two things in the spec are still missing: the owner can speed that up by confirming the return, and the owner can say something went wrong that the borrower did not report. Without disputes, unreported damage cannot be recorded at all, so the one large negative in the policy (`DAMAGED_OR_LOST_UNREPORTED`, -2.0, with the 90-day hold on valuable items) can never happen. Spec 3.3 describes `standing.confirm`, `loan.dispute` and `loan.resolve` and leaves two things to an ADR: whether resolution needs more than one steward, and (implicitly) what happens when nobody can resolve.

## Decision (proposed)

1. **`standing.confirm` for a return.** Author: the loan's owner. Sealed payload `loan`. Valid when the loan has a return with outcome OK or LATE and has not been confirmed or disputed. Effect: the return counts from the confirmation's day instead of waiting out the grace period. If the confirmation's day is within `promptConfirmDays` (3) of the return's day, the owner also earns `CONFIRMED_RETURN_PROMPTLY` (+0.25). Not confirming costs nothing.
2. **`loan.dispute`.** Author: the loan's owner. Sealed payload `loan`, `claim` (`DAMAGE` or `LOSS`), optional `note` (at most 256 characters). Valid only for a return reported OK or LATE (the borrower did not report a problem), once per loan, and within `returnGraceDays` (7) of the return, or within `damageClaimDays` (30) of it for damage or loss found later. It is valid even after the return has already counted.
   Effect: from the dispute's day, both parties' credit for that loan is withheld ("pending review" in the UI), and the borrower's access is not otherwise changed.
3. **`loan.resolve`.** Author: a steward who is neither the owner nor the borrower of the loan. Sealed payload `loan`, `outcome` (`UPHELD` or `DISMISSED`). Valid once per disputed loan.
   - `DISMISSED`: the return counts exactly as the borrower reported it, counted from the resolution's day.
   - `UPHELD`: the borrower gets `DAMAGED_OR_LOST_UNREPORTED` (-2.0) dated the resolution's day, which also starts the incident cooldown (valuable and high-value items on hold for 90 days, essentials and everyday items stay open), and every voucher with a live vouch on that day gets the sponsor nudge of C2. The owner gets nothing for that loan (the same as a reported damage).
4. **Everything is judged the same way as in ADR 0042:** standing is computed from the events earlier in the log at the day of the event being judged, so every client agrees.
5. **Screens.** On Borrow, an owner sees, for each returned loan: "It came back fine" (confirm) and a "Report a problem" disclosure (claim, note); loans in dispute show "Pending review" to both parties. A steward who is not a party sees "Disputes to resolve" with the claim, the borrower's reported outcome and the two buttons. A circle with a single steward shows that steward a warning that disputes in which they are a party cannot be resolved.

## Decisions for the owner

**A. Who resolves.** **A1 (recommended, the spec's default): one steward who is not a party.** Fast, and the borrower and owner are both protected by the rule that a party cannot resolve their own case. **A2: the circle's steward quorum** (`StewardSettings.quorum`, default 2) must agree on the same outcome, using the same voting machinery as steward changes. Safer against one biased steward, but slower, and in a circle with two or three stewards a dispute between two of them can never reach a quorum.

**B. When nobody resolves.** This happens when the only steward is a party, or stewards are inactive. **B1 (recommended): after `damageClaimDays` (30) from the dispute with no resolution it counts as `DISMISSED`**, so a borrower's credit is not held for ever and the owner is never silently overruled: they keep the full claim window to find a steward. **B2: it stays pending** and both credits stay withheld until a steward acts. **B3:** after 30 days it counts as `UPHELD` (favours the owner); not recommended, because it would let one person punish another by inaction of others.

**C. Is the owner's reward for a returned-with-damage dispute upheld?** **C1 (recommended): nothing**, as in point 3. **C2:** the owner keeps the normal lending credit (the item did come back).

## Consequences

- Unreported damage can finally be recorded, and the cooldown that follows is a pause on valuable items, not a ban; the borrower still has essentials and everyday items.
- Disputes are one more place trust can be abused. The guard rails are: only the owner may dispute, only a non-party steward may resolve, one dispute per loan, a time window, a note, and the borrower's honest reporting never being penalised.
- A fold now remembers disputes and resolutions per loan and treats a loan's credit as conditional; the standing function gains inputs but keeps the same shape.
- A steward who is also a regular borrower and lender will occasionally be a party; with two or more stewards another one can resolve, which is why the screens warn about single-steward circles.
- Contribution notes (`standing.note` and its confirmation), pauses and config events remain for later slices.
