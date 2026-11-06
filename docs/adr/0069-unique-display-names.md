# 0069. Display names are unique within a circle

- Status: Accepted
- Date: 2026-11-06
- Supersedes: one point of 0068 ("Two members may have the same name")

## Context

ADR 0068 let two members share a name and relied on the key fingerprint to tell them apart. The owner decided that two people who are both current members must not be able to show the same name: "Bea" twice on a borrow list invites mistakes, and a newcomer copying an existing name is the easiest impersonation there is. Names are sealed (ADR 0004), so the server cannot enforce this; every client must reach the same answer from the same log.

## Decision

1. **Taken.** A name is taken if another current member (one who has not left or been removed) holds a name with the same comparison key, `NameKey.of`: the text is already NFKC and tidy when saved (ADR 0068), and the key lower-cases it with a fixed table in our own code, never the platform's. The table covers ASCII, Latin-1, Latin Extended-A, Greek (final sigma folds to sigma) and Cyrillic up to U+052F. It deliberately leaves alone the Turkish dotted capital I (U+0130) and dotless i (U+0131), the German sharp s and every script without case, so the JVM and the browser give identical results (checked by `NameKeyTest`, run on both).
2. **Order.** Log order (`serverSeq`) decides; events still in the outbox fold after the log, as always. The earlier `member.profile` wins. A later one for a taken name is refused in the fold with the reason `NAME_TAKEN_REASON` and does not change that member's name. A member's own change to a name only they hold (for example `bea` to `Bea`) is allowed. A name is freed when its holder renames or leaves.
3. **First claim refused.** The member stays unnamed, so the app keeps them on the "Your name" page.
4. **App.** `setMyName` checks against the fold of the current view, outbox included, and answers `NameResult.NameTaken(name)` without queueing: "<name> is already used in this circle. Choose another, for example add your surname or initial." If two people claim the same name at once, the fold refuses the later event; `CircleView.nameRefusals` remembers, per member, the name of a newest claim that lost, and `HomeData.nameRefusal` carries the same message. The "Your name" page and Settings show it as an alert until the member claims a name that works, so the loser sees why and is never reset silently.
5. **Server.** It cannot check, because names are sealed. A modified client could send a clashing name; every honest client refuses it in the fold, so it has no effect.

## Known limits

- Look-alike (confusable) characters are not detected: Cyrillic "а" for Latin "a" gives a different key. The fingerprint and the in-person confirmation (ADR 0068) remain the real check of who someone is.
- Case folding covers the scripts listed above. Names in other cased scripts (Armenian, Georgian, Cherokee, and so on) are compared as written.
- A member who left is still known by the name on old records, and a new member may then take that name.
- The tie between two simultaneous claims is the order the server gave them, not the time the people pressed the button.

## Consequences

- Names are unambiguous among current members.
- Every client folds the same way; no platform Unicode tables are involved.
- A rename can be refused after it looked fine on a stale replica; the app says so after the next sync.
