# 0050. Pauses

- Status: Proposed
- Date: 2026-11-04

## Context

A pause is the last resort when a member keeps causing harm (design: "needs several distinct reviewers who are themselves trusted members, a reason, an expiry and an appeal route; essentials stay open"). The access rule already honours a `Pause` (`AccessPolicy.canBorrow`, `tryPause`, config `reviewersForPause` = 3, `pauseReviewerMinTier` = TRUSTED, `defaultPauseDays` = 30), and the event types `pause.propose`, `pause.review`, `pause.lift`, `pause.appeal` are named in the spec but have no payloads, rules or screens. Because a pause is a heavy thing to do to a person, the rules below favour slowness, visibility to the subject and an easy way out.

## Decision (proposed)

1. **Events** (all sealed, `public` empty, like loans): `pause.propose {pause, subject, reason (1 to 280 chars), days}`; `pause.review {pause, agree}`; `pause.appeal {pause, note}` (the subject only); `pause.lift {pause}`.
2. **Proposing**: any member at the reviewer tier or above (`pauseReviewerMinTier`, TRUSTED by default; stewards count as trusted, ADR 0042). The proposer counts as the first reviewer. `days` is 1 to 90, default `defaultPauseDays`. A subject cannot be a steward (a steward is removed by the steward rules, not paused). One open proposal or live pause per subject.
3. **Taking effect**: when `reviewersForPause` distinct reviewers at the reviewer tier, none of them the subject, have agreed within 14 days of the proposal. The pause runs from the day the last needed review arrived for `days` days. A proposal that does not reach that in 14 days lapses.
4. **The subject is told at once**: from the proposal the subject sees it (reason, who proposed) and can answer with one appeal note; reviewers see the note. Only the subject sees who is for and against; others see counts.
5. **Appeal**: an appeal is a note, not a veto. It is shown to every reviewer, and any reviewer may then `pause.lift`; so can any steward who is not the proposer. A pause is also lifted if reviewers withdraw below the number needed (a `pause.review` with `agree = false` from an earlier agreeing reviewer).
6. **Effect**: while live, the subject cannot request anything but essentials (the existing rule). Loans already approved or active carry on and can be returned. The pause never changes standing points.
7. **Screens**: the subject sees "Your access is paused until {date}", the reason, the appeal form and the plain statement that essentials are open. Reviewers see a "Pauses" section on Standing or Members with proposals waiting for them. The access line on items names the expiry and the appeal route.

## Decisions for the owner

**A. Who may propose. A1 (recommended): any member at the reviewer tier.** **A2: stewards only** (fewer pauses, but the community cannot act if stewards are absent).

**B. Who counts as a reviewer. B1 (recommended): any member at the reviewer tier, stewards included.** **B2: stewards only**.

**C. Lifting. C1 (recommended): any reviewer after an appeal, or any other steward, can lift it.** **C2: only by the same number of reviewers needed to place it** (harder to undo, closer to "needs a quorum" both ways).

**D. Number needed. D1 (recommended): the configured `reviewersForPause` (3), but never more than the number of eligible reviewers minus one**, so a small circle can still act. **D2: always the configured number** (a circle with fewer eligible reviewers can never pause anyone).

## Consequences

- Needs payloads, fold rules (`MarketFold` or a new `PauseFold`), the `AccessPolicy` input from the log, app calls and three screens; the server only checks that the events are sealed and sent by a member.
- A small circle (three or four trusted people) can pause someone with D1; the risk of an unfair pause is the reason for the subject's visibility and the easy lift.
- Config events (`config.update`, the owner's `minTier` in the UI) are separate and not decided here.
