# 0053. Contribution notes

- Status: Accepted
- Date: 2026-11-04

## Context

Standing so far comes only from lending and borrowing. The spec also has `standing.note` for contributions that are not loans (`SKILL_SHARE_HELPED` 1.5 points, `HOSTED_OR_ORGANISED` 2.0, `MENTORED_NEWCOMER` 1.5), which count only when the other person confirms them with `standing.confirm`. The point is to recognise people who help without owning tools. The risk is gaming: friends confirming each other's notes. The library already caps credit per counterpart (`maxCreditedPerCounterpart` 3 per 90 days) and the standing calculator already knows these kinds; what is missing is the events, the fold rules and the screens.

## Decision

1. **Events.** `standing.note {note id, kind, with (the other member), text (0 to 140 chars)}` by the member who gives or organises; the other member answers with the existing `standing.confirm` carrying `{note}` (the confirm event already exists for returns; it gains a second shape).
2. **Who earns.** For `SKILL_SHARE_HELPED` and `MENTORED_NEWCOMER` the credit goes to the helper (the author of the note); for `HOSTED_OR_ORGANISED` the credit goes to the host (the author). The other person never earns, only confirms, so confirming costs them nothing.
3. **Counts only when confirmed**, on the day of the confirmation, within 30 days of the note; an unconfirmed note counts for nothing and lapses. A note names someone in the circle other than the author.
4. **Limits against gaming** (all existing library caps apply, plus): at most 3 notes an author may make in any 30 days; `MENTORED_NEWCOMER` is only valid if the other person is at NEWCOMER tier on the note's day; a member cannot confirm a note about themselves from a second device (the confirmer is a member, not a device).
5. **Screens.** A "Thank someone or log a contribution" form on the Standing page (kind, person, short text) and a "Waiting for your confirmation" list with Confirm; standing reasons list the contributions in words.

## Decisions for the owner (answered 2026-11-04: A1, B1, C1)

**A. Which kinds. A1 (recommended): the three in the spec.** **A2: only skill help and mentoring** (no hosting; hosting is hardest to verify).

**B. Who may confirm. B1 (recommended): the named person.** **B2: the named person, plus a steward may confirm for hosted events with several guests** (more work, fewer disputes about group events).

**C. The monthly cap on notes. C1 (recommended): 3 in 30 days per author.** **C2: no extra cap** (rely on the counterpart cap only).

## Consequences

- Needs payloads and fold rules in `MarketFold` and `StandingRules.eventsFrom`, two small forms and a list; the server only checks the events are sealed.
- Standing from contributions is slower and smaller than from lending, on purpose: lending is the thing the circle exists for.
- Contribution kinds cannot be added without a new ADR (the weights are config-able later, ADR 0051 left them out).
