# 0080. Date format setting

- Status: Accepted
- Date: 2026-11-13
- Supersedes: nothing. It extends the browser-only Appearance settings (text size, colours); no ADR fixed how dates were written before, they were simply ISO text.

## Context

Every date a page showed was ISO (`2026-11-13`): in histories, the Borrow page, Standing, pauses, due lines and stewards. That is unambiguous but not how most people read dates, and 11/13 against 13/11 is a real source of mistakes. The owner asked for a setting.

## Decision

1. **Four formats.** `13 Nov 2026` (the default: unambiguous), `13/11/2026` (day first), `11/13/2026` (month first) and `2026-11-13` (ISO). Numeric formats pad the day and month to two digits; the long format does not pad the day. Years have four digits.
2. **A browser-only preference**, like the other Appearance choices: local storage key `circles.dateFormat`, read at start and applied before the first render, never sent anywhere and not part of the circle. An unknown or missing value gives the default.
3. **One helper.** `DateFormats` in shared common code (`show(day, format)`, `showRange`, `DateFormat` with a fixed English month table, no platform locale, so the JVM and the browser write the same text) and `DateText` in the web module, which holds the chosen format for the render in progress. Every view and notice writes a date through `DateText`. `CalendarDay.format` (always `YYYY-MM-DD`) stays for what must not change with the setting: the value of a date input, stored and sent values, and the export file name.
4. **Guard.** `DateFormatSourceScanTest` fails if web view code calls `CalendarDay.format` without an `iso:` comment saying why, or writes an ISO-looking date inside a string.
5. **Settings.** The Appearance section has a "Date format: " select with a live example line ("Example: 2 Nov 2026"). Choosing a format saves it and redraws the page at once (with the other two choices as they stand in the form); Save still works.
6. **Date boxes.** The boxes where a date is picked (`type=date`) show the browser's own format, which a page cannot override. Settings says so, and under each date box a line writes the chosen date in the chosen format (updated as the date changes), so the two can be compared.

## Consequences

- The default changes what people see (ISO to `13 Nov 2026`), and the browser script and view tests match the new text.
- Dates inside reasons produced by the circle rules in shared code still read as day numbers (for example "a new steward cannot vote until day 20766"): that code has no access to the browser's choice. The reason strings and the places this applies to are listed in the commit's report; they are not part of this change.
- `DateText.current` is set once per render from the state and when the saved choice is read, so a render is still a function of its state; tests that call `dueLine` directly set the format first.
- The history search text uses the displayed date, so searching for a date finds it as written.
