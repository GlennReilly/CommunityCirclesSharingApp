# 0061. Approving other dates

- Status: Accepted
- Date: 2026-11-04

## Context

ADR 0052 left out the owner changing an agreed window: the owner could only approve the dates asked for or decline with a note, and the borrower asked again. In practice an owner often says "I can lend it, but from Saturday". The `loan.respond` event already carries the agreed window, and the fold already used it, but the booking check looked at the dates asked for, and the app never offered it. This is a small, reversible rule, so it is recorded here as accepted; supersede it if you want something else.

## Decision

1. When approving, the owner may choose other dates ("Approve with different dates", with the requested dates pre-filled). The dates must be valid as for a request (not in the past, end not before start, at most 90 days).
2. The booking check (ADR 0052) uses the dates being approved, in the app before sending and in the fold.
3. The loan keeps the dates that were asked for (`requestedFromDay`, `requestedToDay`) next to the agreed ones. The borrower's page says "The owner approved other dates than you asked for. If they do not suit you, cancel and ask again."; the borrower can cancel an approval before the hand-over (ADR 0052).
4. Nothing else changes: standing and the late/OK judgement use the agreed end day, as before.

## Consequences

- No new event type and no server change; old logs fold as before (an approval with the asked-for dates is an unchanged loan).
- An owner cannot change the dates after approving; the way out is the borrower cancelling and asking again.
