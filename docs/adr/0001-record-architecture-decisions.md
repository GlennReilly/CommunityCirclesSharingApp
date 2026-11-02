# 0001. Record architecture decisions

- Status: Accepted
- Date: 2026-11-02

## Context

The project is meant to outlive its author and to be handed to other maintainers. Reasons for decisions must survive in the repository, not in someone's head or a chat.

## Decision

Keep ADRs in `docs/adr/`, one short file each (context, decision, consequences), numbered `NNNN-slug.md`, with `README.md` as the index and `template.md` as the template. Write one whenever a decision is hard to reverse, changes a rule, picks a technology, or answers a question the project says to ask first. An accepted ADR is not edited to change the decision; a new ADR supersedes it.

## Consequences

Small cost per decision. The spec's "decide and record in an ADR" instructions point here. (The spec originally named `docs/decisions/`; v0.2 uses `docs/adr/`.)
