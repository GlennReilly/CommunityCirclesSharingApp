# 0081. The UI says "approve access", not "let in"

- Status: Accepted
- Date: 2026-11-13
- Supersedes: only the "let in" wording of ADR 0071 and the consequence of ADR 0078 that says button labels and headings with "let in" were not changed. Neither is edited; the check page, the in-person tick and everything else in them stand.

## Context

ADR 0078 changed the notice a steward sees after letting a newcomer in to "<name>'s access has now been approved." The buttons, headings and messages still said "let in", so one action had two names. The owner decided the whole UI says approve access.

## Decision

1. The words are: **Approve access** (the button and the check-page heading), **Check and approve access** (the button on the waiting list), **Waiting for approval** (the Members heading) and "1 person waiting for approval" / "N people waiting for approval" (the Home link), "Approving access: <name>" (the check page), the help text "Approving access gives this device the circle's key. Do it only after you have checked in person who the person is.", and "Nobody is waiting for approval with that device any more: the newcomer's access has been approved, or they have left."
2. A newcomer is told "A steward needs to approve your access before you can ..." (see anything, see or add items, borrow or lend, see or offer skills), in the same words in the notices (add a device, make a recovery code, set your name, add items, borrow, vouch).
3. The Stewards page says "Stewards approve new members' access, decide disputes, and decide who the stewards are." and "...nobody can approve new members' access or decide disputes that involve them."
4. Identifiers, ids, action names and sealed values keep their names (`let-in-` form ids, `NotLetInYet`, `FORM_LET_IN_PREFIX`).
5. Guard: `StandaloneWordsSourceScanTest` fails for a string literal in the shared and web code that says "let in", "let them in", "let you in", "letting in", "been let in" and the like. Comments and identifiers are not scanned.

## Consequences

- One action has one name everywhere. The browser script and view tests match the new words.
- The old words survive in accepted ADRs and code comments as history.
