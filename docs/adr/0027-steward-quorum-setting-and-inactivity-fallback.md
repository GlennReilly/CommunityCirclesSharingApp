# 0027. Steward quorum as a circle setting, with an inactivity fallback

- Status: Proposed (supersedes 0023 once accepted)
- Date: 2026-11-03

## Context

ADR 0023 fixed the steward quorum at 2. That protects larger circles from one bad steward, but it fails small genuine ones: a circle run by one person, or by a person and a helper, either has no protection to give or can deadlock when the helper goes quiet (the active steward can then neither add nor remove a steward, only leave). The owner wants genuine single-steward circles to keep full control, while larger circles keep the protection.

## Decision

1. **Quorum is a circle setting.** `stewardQuorum` is a whole number from 1 up. It is set by the founder in the `public` part of `circle.create` (default 2 when absent), so the server and every client see it. Quorum 1 means any steward acts alone, and is meant for a solo or very close circle.
2. **Changing it** uses a new event `steward.quorum` with `public = {quorum}` and an optional note. It is voted like `steward.add`: it takes effect when the current quorum of distinct stewards have each sent one for the same value within the proposal window. Lowering and raising follow the same rule, so one steward cannot lock the others out by raising it, or strip protection by lowering it.
3. **Voting rules of ADR 0023 stand** when the quorum is 2 or more: votes count by distinct member, a proposal expires after 14 days, a steward added later waits 7 days before their vote counts, the last steward can never be removed, a steward may always leave while another remains, a two-steward circle with quorum 2 cannot remove either (the other can only leave), and the target of `steward.add` must have the STEWARD standing tier in the folding client (still a hook until M4).
4. **When the quorum is 1 there is no cool-off and no window**: a steward's `steward.add`, `steward.remove` or `steward.quorum` takes effect at once. A steward can then remove another steward alone; that is the price of the setting.
5. **Inactivity fallback.** A change can take effect with fewer votes than the quorum if the other stewards have gone quiet. It applies to `steward.add`, `steward.remove` and `steward.quorum`, and is evaluated when the proposer repeats the same event on or after the fallback day. It takes effect only if all of these hold, judged from the signed `day` claims and the log order:
   - the proposer's earliest matching event is at least `stewardFallbackDelayDays` (30) old on the day of the repeat;
   - on the day of that earliest event, every other steward had sent no event for at least `stewardInactiveDays` (90);
   - no other steward has sent any event since that earliest one (any activity cancels the fallback, and works as a veto).
   The last-steward rule and the quorum floor of 1 still apply, and the fallback is never used when the quorum is 1.
6. **Visibility.** Clients show the quorum, and that a circle has a single steward, in the circle's settings and to anyone about to join. A pending change, and a fallback counting down, are shown to every member and steward as soon as the first event arrives.
7. **Enforcement.** The server enforces 1 to 5 (new `steward.quorum` event, `circles.steward_quorum` column, votes table reused), using only public data. Clients enforce 1 to 6 when folding. The shared `StewardRules` function gains the quorum, the activity facts and the fallback, so server and clients still share one implementation.
8. After a steward leaves or is removed, clients should rotate the circle key and re-split recovery shares (as in 0023). A one-steward circle has no recovery shares (a split needs two); its protection against losing the steward's devices is the optional member recovery code.

## Consequences

- A solo steward is never limited: they can add helpers, rotate keys, revoke devices and, with quorum 1, change stewards at once. A founder who wants protection later can add a second steward and raise the quorum.
- A circle that chooses quorum 1 has no defence against one bad or compromised steward. Members see this before joining; it is the owner's choice for their own circle.
- The fallback frees a circle whose helper went quiet, but a steward who is genuinely away for more than 90 days and sends nothing can be removed by a co-steward 30 days after the proposal. Any event from the absent steward in that window stops it. Stewards who plan a long absence should post something (even a profile edit) before they go and when they return.
- One more event type, one migration, more fold state to test. The thresholds (90 and 30 days, quorum floor) are policy constants and need a new ADR to change.
