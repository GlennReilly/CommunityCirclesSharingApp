# 0079. A steward needs a confirmed identity

- Status: Accepted
- Date: 2026-11-13
- Supersedes: nothing. ADR 0027 (steward governance) is unchanged; this adds an eligibility rule on top of it. ADR 0068 (display names and their confirmation) supplies what "confirmed" means.

## Context

Stewards let people in, decide disputes and decide who the stewards are, yet nothing stopped a member whose identity nobody had checked from becoming one. The only hook, `CircleFold(mayBecomeSteward = ...)`, defaulted to allow everyone and had no data to decide with.

## Decision

1. **Rule.** A member whose identity has not been confirmed cannot become a steward. "Confirmed" is ADR 0068's: someone allowed to confirm (Level 2 or above, or a steward) confirmed the member's current name, `NAME_CONFIRMATIONS_NEEDED` times (1 today).
2. **Where it applies.** Every `steward.add` event is checked when it is folded, so it covers the first proposal, each later vote, the moment the change takes effect, and the inactivity fallback (a repeated proposal): an event for an unconfirmed member is refused ("the member's identity has not been confirmed yet, so they cannot become a steward") and never counts as a vote.
3. **Exemptions.** The founder (author of `circle.create`) is exempt, because nobody else could have confirmed them. Anyone who already is a steward is exempt. A confirmation lost later (the confirmer leaves) does not remove a sitting steward; a rename ends the confirmed stretch, so a renamed member must be confirmed again before a later `steward.add` counts, and a steward already in office stays one.
4. **Time matters.** A member counts as confirmed from the confirmation event until a rename, as stretches of the log (`IdentityInterval`, recorded in `MarketFold`). A `steward.add` is judged by where it sits in the log, so a confirmation that comes after a proposal does not make the earlier proposal valid; the member is proposed again.
5. **A client-fold rule, not a server rule.** Names and confirmations are sealed events, so the server cannot see them and its `EventRules` does not check this, as with loans and pauses; it relies on every client folding the same way. `CircleFold(requireConfirmedStewards = true)` enforces it and `Replica.fold` (what the app uses) turns it on; the default is off so folds of plain governance logs (tests, tools) need no names. The rule and the confirmations depend on each other (a steward counts as a confirmer), so the fold starts by allowing everyone, reads the confirmations that result, and folds again with the stretches they give, until the stewards and refusals stop changing (at most 6 rounds). A log with no `steward.add` is folded once.
6. **Words and screens.** The app checks before it sends: "<name>'s identity has not been confirmed yet, so they cannot become a steward." ("The member's ..." with no name). The Stewards page "Propose a new steward" lists only confirmed members and, when some are left out, says in one sentence why ("Only members whose identity has been confirmed can become stewards, so some members are not listed. Open a member's page and check their identity in person first."), or "No member can be proposed yet.". A steward looking at an unconfirmed member's page reads "This member cannot become a steward until someone has confirmed their identity."

## Consequences

- A member cannot be both keyless and a steward: naming yourself needs the circle key, so someone who never got the key cannot be confirmed. The recovery-share test now makes its keyless steward by changing the key after the steward joined.
- A device that is behind the log may disagree with one that is not for a moment; once both have the same log they agree, because the rule depends only on the log.
- Circles made before this rule keep their stewards (they are already stewards); only new `steward.add` events are judged.
- The server accepts an unconfirmed `steward.add` and every honest client ignores it.
