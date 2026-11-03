# 0027. Steward governance settings and the inactivity fallback

- Status: Accepted (supersedes 0023; the minimum of `stewardInactiveDays` was lowered to 7 by ADR 0029)
- Date: 2026-11-03

## Context

ADR 0023 fixed the steward quorum at 2. That protects larger circles from one bad steward, but it fails small genuine ones: a circle run by one person, or by a person and a helper, either has no protection to give or can deadlock when the helper goes quiet (the active steward can then neither add nor remove a steward, only leave). The owner wants genuine single-steward circles to keep full control, larger circles to keep the protection, and the stewards themselves to set how long "quiet" means, through the interface, within sensible limits.

## Decision

1. **Three circle settings**, visible to everyone and set by the stewards:

   | Setting | Default | Minimum | Maximum |
   |---|---|---|---|
   | `stewardQuorum` | 2 | 1 | 9 |
   | `stewardInactiveDays` (how long the other stewards must have been silent) | 60 | 30 | 365 |
   | `stewardFallbackDelayDays` (how long a lone steward's proposal must wait) | 14 | 7 | 90 |

   The minimums are fixed in the code and need a new ADR to change; they stop a bad steward from setting a one-day trigger. Quorum 1 means any steward acts alone, for a solo or very close circle.
2. **Initial values** are set by the founder in the `public` part of `circle.create` (three optional keys; absent means the defaults), so the server and every client see them from the first event.
3. **Changing them** uses a new event `steward.policy` with `public = {quorum, inactiveDays, fallbackDelayDays}` (all three, the full new values) and an optional note. It is voted like `steward.add`: it takes effect when the current quorum of distinct stewards have each sent one for the same three values within the proposal window. Raising or lowering follows the same rule, so one steward cannot lock the others out by raising the quorum, or strip protection by lowering it. **The inactivity fallback is not available for `steward.policy`**, so it cannot be used to weaken the settings first.
4. **Voting rules of ADR 0023 stand** when the quorum is 2 or more: votes count by distinct member; a proposal expires after 14 days; a steward added later waits 7 days before their vote counts (founding stewards never wait); the last steward can never be removed; a steward may always leave while another remains; removing a steward needs the quorum among the *other* stewards, so in a two-steward circle with quorum 2 a removal can never gather its votes: the proposal is recorded and waits, and only the inactivity fallback (point 6) or the steward leaving ends it; and the target of `steward.add` must have the STEWARD standing tier in the folding client (still a hook until M4). The proposal window and the 7-day cool-off are fixed policy, not settings.
5. **When the quorum is 1 there is no cool-off and no window**: a steward's `steward.add`, `steward.remove` or `steward.policy` takes effect at once. A steward can then remove another steward alone; that is the price of the setting.
6. **Inactivity fallback.** A `steward.add`, `steward.remove`, or a change that cannot reach the quorum, takes effect with fewer votes if the other stewards have gone quiet. It is evaluated when the proposer repeats the same event on or after the fallback day, and takes effect only if all of these hold, judged from signed `day` claims and log order, using the settings in force at that moment:
   - the proposer's earliest matching event is at least `stewardFallbackDelayDays` old on the day of the repeat;
   - on the day of that earliest event, every other steward had been silent for at least `stewardInactiveDays` (a steward who has never sent an event counts from the day they became one; founding stewards from the start of the circle);
   - no other steward has sent any event since that earliest one (any activity cancels the fallback and works as a veto).
   The last-steward rule and the minimums still apply, and the fallback does nothing when the quorum is already 1.
7. **Visibility and the interface.** Clients show all three settings, and that a circle has a single steward, in the circle's settings screen and to anyone about to join. Stewards change them there; the screen shows pending proposals, the votes needed and, during a fallback, the days left. A pending change, and a fallback counting down, are shown to every member and steward as soon as the first event arrives.
8. **Enforcement.** The server enforces 1 to 6 (new `steward.policy` event, three `circles` columns, the votes table reused with a position, last-activity queries) using only public data. Clients enforce all of it when folding. The shared `StewardRules` function takes the settings and the activity facts, so server and clients still share one implementation.
9. After a steward leaves or is removed, clients should rotate the circle key and re-split recovery shares (as in 0023). A one-steward circle has no recovery shares (a split needs two); its protection against losing the steward's devices is the optional member recovery code.

## Consequences

- A solo steward is never limited: they can add helpers, rotate keys, revoke devices and, with quorum 1, change stewards at once. A founder who wants protection later can add a second steward and raise the quorum.
- A circle that chooses quorum 1 has no defence against one bad or compromised steward. Members see this before joining; it is the owner's choice for their own circle.
- The fallback frees a circle whose helper went quiet, but a steward who is away longer than `stewardInactiveDays` plus `stewardFallbackDelayDays` (74 days at the defaults, 37 at the minimums) and sends nothing can be removed by a co-steward. Any event from the absent steward before it takes effect stops it. Stewards planning a long absence should raise the setting or post something before they go and when they return.
- Stewards who set the times short on purpose accept that risk for their circle. The floors (30 and 7 days) keep it from being trivial.
- One more event type, one migration, more fold state to test.
