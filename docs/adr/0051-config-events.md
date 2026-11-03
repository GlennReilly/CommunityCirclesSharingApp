# 0051. Config events

- Status: Accepted
- Date: 2026-11-04

## Context

Standing, vouching and pauses run on `CircleConfig` (tier thresholds, half-life, vouches needed, reviewers for a pause, cooldowns, event weights). Today every circle uses the library defaults (`StandingRules.defaultConfig`). The spec (section 3.3 and 9, M4) has a `config.update` event, written by a steward, validated at fold time (`CircleConfig.validate()`, fall back to defaults if it reports problems), and says the defaults are to be tuned after the pilot. A change must not rewrite history: a loan request that was valid on the day it was made must stay valid.

## Decision

1. **Event.** `config.update`, sealed like the loan events (`public` empty), carrying the whole config as a map of the chosen settings (see B), an `effectiveDay` (today or up to 30 days ahead) and a note. Unknown keys are refused. Out-of-range values are refused (the existing `validate()` plus ranges for each number).
2. **Versioning by day** (C1). The fold keeps the list of accepted configs sorted by effective day, and every check that depends on config (access decisions, vouch tier, pause reviewers, standing) uses the config in force on the day of the thing being checked. A request made yesterday is judged by yesterday's rules for ever. The default config is in force from the start.
3. **Standing shown to a member** is computed on today's day with today's config.
4. **Each setting changes by the same vote** as the steward rules (A1).
5. **Screen.** A "Circle rules" section on the Stewards page: current values in plain words, a form for the editable settings, pending votes with Agree like steward proposals, and the next effective day. The owner's `minTier` for one item is a separate field on the item form (the event already has it).

## Decisions for the owner (answered 2026-11-04: A1, B1, C1)

**A. Who may change it. A1 (recommended): the steward quorum**, voted exactly like `steward.policy` (the same quorum, window and cool-off). **A2: any single steward**, which is faster but one steward could change the rules for everyone.

**B. What can be changed. B1 (recommended): a short list**: the three tier thresholds, half-life, vouches needed for Neighbour, vouch validity, reviewers needed for a pause, default pause days, incident cooldown. **B2: everything**, including the per-event point weights and counterpart caps (more tuning room for the pilot, easier to make a rule nobody understands). **B3: nothing yet**: keep the defaults for the pilot and decide from what it shows (no code).

**C. How a change applies. C1 (recommended): by day**, as in point 2. **C2: to everything, retroactively**: simpler to build, but an old valid loan request could become invalid and the app would show a history that disagrees with what people saw.

## Consequences

- C1 means the fold's config becomes a function of the day: `MarketFold`, `StandingRules` and the app's summaries take a config lookup instead of one value, and the tests need cases that cross a change day.
- Because the rules arrive from the log, a bad config must never break the app: invalid ones are refused at fold time and the previous config stays.
- The pilot's lessons (open decision 6) can then be applied without a new release.

## As built (2026-11-04)

`config.update` is sealed like the loan events and carries nine whole numbers (`ConfigSettings`: the three thresholds in tenths of a point, half-life, vouches needed, vouch validity, reviewers for a pause, default pause days, incident cooldown), with ranges in `ConfigSettings.problem` and the library's `validate()` on top. There is no separate `effectiveDay`: the rules take effect on the signed day of the vote that completes the quorum (`min(steward quorum, stewards)` distinct stewards sending the same numbers within 14 days; a new steward waits out the 7-day cool-off unless the quorum is 1), which keeps the order of events deterministic. `MarketFold` keeps `configs` sorted by that day and every check uses `config(day)`; `MarketView.configOn(circle, day)` gives the same to the app. A proposal that is already the rules in force is refused. The "Circle rules" section on the Stewards page shows the rules in words to every member and the proposal form and Agree buttons to stewards; the item form has "Who may ask" (Neighbour or Trusted and above; a value below the kind's default is ignored).
