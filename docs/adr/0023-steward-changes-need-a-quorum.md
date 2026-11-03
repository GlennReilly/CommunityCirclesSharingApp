# 0023. Steward changes need a quorum of distinct stewards

- Status: Superseded by 0027
- Date: 2026-11-02

## Context

ADR 0022 let any single steward add or remove stewards. One bad steward could invite a fake member, join as them, and make them a steward, or remove the honest stewards, with no one else involved. The spec's "quorum rules below" (section 3.3) were never written. Circles are small, members are mostly known to each other, and the server operator is not trusted, so the rules must be checkable by clients as well as the server.

## Decision

1. **Quorum.** The quorum is 2 (setting `stewardQuorum`, never below 2). A circle with fewer stewards than the quorum uses its steward count, so a circle with one steward has a quorum of one and no protection. Clients and the UI flag such a circle.
2. **Counting.** Votes are counted by distinct member id, never by device. A steward's second device, or the same steward sending twice, counts once.
3. **Adding.** `steward.add` stays an ordinary event with `public = {member}`. It takes effect when the required number of distinct stewards have each sent a `steward.add` for the same member within `stewardProposalDays` (14) of each other. The first event is the proposal; later ones are approvals. No new event type is needed.
4. **Removing.** `steward.remove` works the same way, but the target's own events do not count, and the quorum must be met by other stewards. Consequently a circle with two stewards cannot remove either: only a steward may remove themselves, by sending `steward.remove` for their own member, which takes effect alone as long as another steward remains. The last steward can never be removed.
5. **Cooling-off.** A steward added after circle creation cannot count towards a quorum until `stewardCoolOffDays` (7) after the `day` of the event that made them a steward. Stewards present at `circle.create` have no cooling-off. The server can enforce this, because both days are in signed public parts (they are still untrusted claims, see spec 3.1).
6. **Standing (clients only).** The target of `steward.add` must have reached the STEWARD standing tier (spec 2.1) in the folding client's own computation. Standing is private to members, so the server cannot check it; a client ignores a `steward.add` whose target is below the tier. This makes the tier binding in the fold, where the spec called it advisory.
7. **Where each rule is enforced.** Server: 1 to 5, using the stewards table, a new table of pending proposals, and the days in the events, to keep spam out. Clients: 1 to 6 when folding. A malicious operator who accepts a change the rules forbid cannot make honest clients accept it.
8. **After a steward leaves or is removed,** clients should rotate the circle key and re-split recovery shares (ADR 0020, 0021). This stays a client rule.

## Consequences

- One bad steward can no longer promote an accomplice or remove others. Capturing a circle needs two stewards, or one steward plus one honest steward who is deceived.
- A fake account still needs two real stewards' approval and enough standing, so inviting a throwaway member is not enough.
- Circles with a single steward are unprotected; the first thing a founder should do is add a second steward, which itself needs a quorum of one, so it works. After that, changes need both.
- A two-steward circle cannot remove a misbehaving steward; they can only leave. Circles that expect to need removal should have at least three stewards. A dispute between two stewards in such a circle is resolved by members leaving and forming a new circle.
- Slower governance: proposals expire, and a new steward waits a week before they can vote.
- Supersedes ADR 0022. The steward code written under that ADR (any steward acts alone, in `EventRules` and `CircleFold`, and `StewardTest`) must change to follow this one before steward changes are relied on.
