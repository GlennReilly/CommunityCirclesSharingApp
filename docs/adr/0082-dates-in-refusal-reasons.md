# 0082. Dates inside refusal reasons are placeholders the page fills in

- Status: Accepted
- Date: 2026-11-13
- Supersedes: nothing. It finishes ADR 0080 (date format), which listed "a new steward cannot vote until day 20766" as a date it could not route through the helper.

## Context

Two reasons produced by the circle rules (`StewardRules.decide` for a steward vote and `MarketFold.configUpdate` for a rules vote) said "a new steward cannot vote until day 20766", a raw day number. That code is shared by the JVM and the browser, runs the same everywhere, and does not know which date format a person chose.

## Decision

1. A reason that holds a date carries a **placeholder**, `{date:<day number>}`, made by `ReasonDates.token(day)` (shared code, `circles.app`). The rules never format a date, so the fold produces the same reasons on every platform and in every test.
2. The place that shows the reason fills it in. The web page does it through `Notices.sentenceOf(reason)`, which writes the date with `DateText` in the person's chosen format (ADR 0080), starts the sentence with a capital and ends it with a full stop. Every result type that carries a reason (steward, rules, pause, skill, photo, name, note, recovery) goes through it.
3. The server and logs keep their own wording: `EventRules` passes a steward reason through `ReasonDates.plain`, which writes the date as ISO (`2026-11-20`), so API messages and logs never hold a placeholder.
4. Both reasons now read "A new steward cannot vote until <date>." (a complete sentence, ADR 0077).
5. Guard: `DayNumberSourceScanTest` fails for a string in the shared and web code with a day number after "until", "on", "from", "by", "before", "after", "since", "at" or "of". Counts of days ("30 days") are not dates and are fine. `EventRules` messages are not scanned.

## Consequences

- No placeholder or day number reaches a person; tests cover the four formats, several placeholders, negative days and malformed placeholders.
- Two server messages that name a day number ("day N is after today (M) plus one" and "invite expired on day N") are API diagnostics for clients and logs, not shown to people, and are left as they are.
- A new reason that holds a date uses `ReasonDates.token`; the scan catches the old form.
