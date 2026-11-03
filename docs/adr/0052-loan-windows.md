# 0052. Loan windows: no double booking, and cancelling

- Status: Accepted
- Date: 2026-11-04

## Context

Two members could ask for the same item on the same days and the owner could approve both, because nothing compared windows. A borrower also had no way to withdraw a request or an approval. This was the first item on the pilot list (`docs/pilot-readiness.md`). The owner's wishes are not in dispute here, so the smallest rules that stop a clash were chosen; either can be superseded.

## Decision

1. **Booked means approved or out.** A window is booked by a loan that is `APPROVED` or `ACTIVE` (not yet returned) for the same item; days count inclusively on both ends. A `loan.request` for a window that overlaps a booked one is refused by the fold ("the item is already promised for some of those days"), and so is an approval of a request whose window now overlaps a booked one. Several people may still ask for the same days: the owner chooses.
2. **Declining is always possible.** Only approving is checked.
3. **Cancelling.** A new sealed event `loan.cancel {loan}` by the borrower moves a `REQUESTED` or `APPROVED` loan to `CANCELLED`; after the hand-over it is refused. A cancelled loan books nothing and earns or costs no standing. The item key already wrapped to the borrower stays with them (they read the details they were once shown).
4. **Not built:** the owner changing an agreed window (the owner declines with a note and the borrower asks again), and holding a window while a request waits.

## Consequences

- The app tells the person plainly ("Someone else has been promised that item for some of those days") and checks first, so no event is queued for a clash.
- A late approval that loses a race is refused by every client's fold, so it is ignored everywhere in the same way.
- `LoanStatus` has a new value, `CANCELLED`; the server accepts `loan.cancel` opaquely like the other loan events.
