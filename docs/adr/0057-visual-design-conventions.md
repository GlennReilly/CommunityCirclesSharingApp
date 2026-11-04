# 0057. Visual design conventions

- Status: Accepted
- Date: 2026-11-04

## Context

The first screens were styled only enough to pass the accessibility checks. The owner's screenshots showed forms with labels and fields running along one line, a status message hanging outside the page column, and a bare text "Show" button on the passphrase field. The owner asked for the standard, best-practice arrangement and for design to follow best practice from now on.

## Decision

1. **Page frame:** a header bar (app name left, sync status and Sync/Lock buttons right), a navigation bar with the current page marked by an underline and bold, then a notice banner and the content, all in one centred column of at most 60 rem.
2. **Forms:** one column; each label above its field, fields fill the form up to 34 rem; the one submit button is filled (primary), other buttons are outlined; tick boxes are the box first, then its sentence.
3. **Lists of things** (items, skills, members, loans) are bordered cards; reading lists stay plain bullets.
4. **Notices** are a banner inside the column with the message on the left and Dismiss on the right.
5. **Passphrase fields** have the standard eye icon inside the field: an open eye to reveal, a crossed eye when revealed. The button keeps one name ("Show passphrase") and reports its state with `aria-pressed`; the icon is drawn in CSS so it follows the text colour in light and dark.
6. Light and dark come from the system (`color-scheme: light dark`, system colours, `color-mix` for borders); the primary colour has a light and a dark value that pass contrast.
7. Controls stay at least 44 px high and keep a visible focus outline. The browser script can save every page state as a PNG (`SHOTS=folder`, `DARK=1`) so layout is looked at, not guessed.

## Consequences

- Design choices are made once in `styles.css`; pages differ only in markup (a few classes: `cards`, `secret-field`, `check-row`).
- Any new screen is checked in a screenshot in both colour schemes before it is called done.

## Addition (2026-11-04): working buttons

Every click and submit goes through `busyLaunch`: the button gets `aria-busy="true"` while the work runs, which shows a spinner after its text, makes it ignore further clicks, and tells screen readers it is busy. With reduced motion the spinner is a static "…". It matters most for unlocking (Argon2 takes a second or more) and syncing.

## Addition (2026-11-04): icon-only tool buttons

The header's Sync and Lock buttons are square icon buttons with the standard glyphs (circular arrows for sync, a padlock for lock), a spoken name (`aria-label`) and a tooltip (`title`). While syncing the arrows turn. Words are kept for buttons whose meaning is not a universal icon (Add item, Report it back, Let in).
