# 0022. Steward changes: any steward acts, the last steward stays

- Status: Superseded by 0023
- Date: 2026-11-02

## Context

The spec lists `steward.add` and `steward.remove` with "quorum rules below" (section 3.3), but no rules follow. Recovery shares (ADR 0020) and rotation need more than one steward, and the server must enforce at least the role check (section 3.1).

## Decision

- `public` is `{member}`, the target member id; the optional note is in `encrypted`.
- Any current steward may send `steward.add` for a member who is in the circle and not already a steward, and `steward.remove` for a current steward.
- A circle always keeps at least one steward: removing the last one is refused. A steward may remove themselves while another remains.
- No vote or quorum in v1. Pause and dispute quorums (M4) are separate and unaffected. The server enforces these rules for spam control; clients re-check them while folding.
- Removing a steward does not rotate the circle key by itself. The client should rotate and re-split recovery shares afterwards; this is a UI rule, not a server rule.
- A re-added steward gets their row back (`removed_seq` cleared).

## Consequences

- One compromised steward can add accomplices or remove the others. That fits a small trusted circle; a larger or more adversarial deployment would need a quorum, which would be a new ADR superseding this one.
- The "single steward" warning in the UI (spec 3.3, dispute) still applies to circles that never add a second steward.
