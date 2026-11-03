# 0029. Lower the minimum silent days of the steward fallback to 7

- Status: Accepted
- Date: 2026-11-03

## Context

ADR 0027 set the minimum for `stewardInactiveDays` (how long the other stewards must have been silent before a lone steward's change can use the inactivity fallback) at 30 days, and said changing a minimum needs a new ADR. The owner wants stewards of small, active circles to be able to set it much shorter, down to one week.

## Decision

- `MIN_STEWARD_INACTIVE_DAYS` becomes **7** (it was 30). The default stays 60 and the maximum 365. The other limits of ADR 0027 are unchanged: quorum 1 to 9, `stewardFallbackDelayDays` 7 to 90.
- Nothing else about the fallback changes: the delay, the "no activity since the first proposal" veto and the exclusion of `steward.policy` all still apply.

## Consequences

- The shortest possible fallback is now 14 days (7 silent plus a 7-day delay), against 37 before.
- At that setting a steward who is away for two weeks and sends nothing can be removed by a co-steward, if the co-steward starts the proposal after the first week of silence and repeats it a week later. Any event from the absent steward in between stops it. This is a real risk for circles with holidays or poor connectivity; the settings screen should warn when a steward picks fewer than 30 days.
- The change is one constant and its tests. Existing circles keep their stored values, all of which were at least 30.
- Amends ADR 0027 point 1 and its consequences; 0027 stays accepted for the rest.
