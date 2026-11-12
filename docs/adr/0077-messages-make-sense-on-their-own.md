# 0077. Messages make sense on their own

- Status: Accepted
- Date: 2026-11-12
- Supersedes: nothing.

## Context

Notices, alerts and refusals appeared after an action and said things like "Let in. They can read the circle once their device syncs." or "That cannot be done right now.": "They" and "That" have nothing to point at once the previous screen is gone, and notices clear themselves after 8 seconds.

## Decision

1. Almost every message shown to people is a complete plain-language sentence that names its subject. Only bare confirmations such as "Saved." and "Done." may stand alone.
2. A notice about a person says who, using the display name, or "the new member" / "that member" when there is no name (`Notices` in `web/.../Notices.kt`: `letIn`, `letInFailed`, `vouched`, `vouchRefused`, `memberRemoved`, `nameFor`). Notices stay plain text (ADR 0076).
3. The catch-all refusals ("That cannot be done right now.", "You cannot do that right now.") are replaced by sentences that say what could not be done ("Your name could not be saved right now. Try syncing, then try again."). "Done. ..." / "Sent. ..." fragments say what was done.
4. Reading grade stays low, labels still end with ": ", tier words still come from `TierText`, and nothing reads as a penalty.
5. Guard: `StandaloneWordsSourceScanTest` fails for a string literal in the shared and web code that starts with "They ", "Them ", "It ", "He " or "She ", or that is one of the old catch-all sentences. A small allow-list holds the three button labels on the loan card in front of the reader ("It came back fine", "It came back damaged", "It did not come back").

## Consequences

- About 60 strings changed; the browser script and view tests match the new words.
- A pronoun after its subject in a sentence is fine; only messages that start with one are caught.
- Button labels and headings are not messages and were left alone apart from the three allow-listed ones.
