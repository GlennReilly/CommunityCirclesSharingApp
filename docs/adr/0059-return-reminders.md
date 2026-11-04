# 0059. Return reminders

- Status: Accepted
- Date: 2026-11-04

## Context

A loan has an agreed end day, and people forget. The design says notifications come "via email or SMS where the host configures it; never push-only", and the spec lists `CIRCLES_SMTP_*` as an optional setting; push-only notifications are a non-goal. The server cannot read anything about a loan (events are sealed), cannot know an email address (none is stored), and the circle's privacy model gives it only public ids and days. So a reminder cannot be sent by the server without a new, plainly visible piece of data. Meanwhile the app already folds every loan on the device, so it can always say what is due when it is opened.

## Decision

1. **In the app, always.** Home and the Borrow page show what is due: "Due back today", "Due back in N days" and "Overdue by N days", for the member's own borrowed items, and "Out with member X, due ..." for the owner's. The Home page gets a line with the count, and the document title carries a dot when something is overdue. No notification is sent; people see it when they open the app.
2. **Wording stays kind.** "Overdue" never appears next to a name that others see, and nothing here changes standing: lateness is only recorded when the borrower reports the return (ADR 0043), as before.
3. **Owner nudge.** An owner whose item is overdue has one button on the loan, "Ask them to return it", which only shows the borrower's fingerprint and says to ask in person or in the group's own chat. The app sends nothing.

## Decisions for the owner (answered 2026-11-04: A1, B1, C1)

**A. Email. A1 (recommended): none in v1.** The server would have to learn members' email addresses, which breaks "the server sees only sealed data" and needs SMTP set up by every host. **A2: optional email reminders**, each member typing an address that is sealed to the host's reminder service: a new component, a new trust point and operator setup; best left until the pilot shows people forget even with the in-app lines.

**B. Browser notifications. B1 (recommended): none.** They need push services (a third party), which the project excludes. **B2: local notifications only while a tab is open**: little value, since the in-app line is already visible then.

**C. Where it shows. C1 (recommended): Home, Borrow and the page title dot.** **C2: Borrow only**, quieter.

## Consequences

- Small to build: derived from loans already in the fold, a few lines of text, tests on the day arithmetic and wording; no server change and no new data.
- It only helps people who open the app. If the pilot shows that is not enough, A2 is the next step and gets its own ADR.

## As built (2026-11-04)

`dueLine` (plain words with the date: "Due back today", "Due back in N days", "Back was expected N days ago", no word "overdue") on every loan that is out, on the Borrow page; the owner of a loan whose day has passed gets a disclosure "Ask them to return it" with the borrower's fingerprint and a reminder that Circles sends nothing; `dueCount` feeds a Home line ("N loans are due back today or past their day") and a "(N due)" prefix on the page title. Nothing touches standing or the server.
