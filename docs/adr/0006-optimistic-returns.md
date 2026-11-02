# 0006. Optimistic returns with owner dispute

- Status: Accepted
- Date: 2026-11-02

## Context

Requiring the owner to confirm every return lets owner silence block a borrower's standing. Penalising owners for not confirming would conflict with the non-punitive principle and discourage lending, where owners are the scarce resource.

## Decision

A loan is real only through two-party steps: request, owner approval, then a hand-over confirmed by both. For such a loan the borrower's `loan.return` counts after `returnGraceDays` (default 7) unless the owner sends `loan.dispute`, or immediately on the owner's `standing.confirm`. Prompt confirmation (within `promptConfirmDays`, default 3) earns a small positive bonus; there is no penalty for owners. A dispute withholds credit until a steward who is not a party sends `loan.resolve`. Damage found later can be disputed within `damageClaimDays` (default 30).

## Consequences

Owner silence never hurts anyone. Fake loans still need the owner's cooperation. Whether resolution needs more than one steward is an open decision.
