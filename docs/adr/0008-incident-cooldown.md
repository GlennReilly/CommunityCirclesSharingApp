# 0008. Incident cooldown instead of negative standing

- Status: Accepted
- Date: 2026-11-02

## Context

Standing is clamped at zero so nobody is ever "banned". The side effect: the one sizeable negative (unreported damage or loss) costs a low-standing member nothing, because they are already at the floor.

## Decision

An unreported incident starts a cooldown of `incidentCooldownDays` (default 90). During it, VALUABLE and HIGH_VALUE items are on hold; essentials and everyday items stay open. Points stay clamped. The reason shown says how many days remain and that it is a pause, not a ban.

## Consequences

The consequence is visible, time-limited and equal for everyone. Sponsors still receive a mild nudge. The default length is a guess to revisit after the pilot.
