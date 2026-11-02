# 0005. Tiers are advisory; owners decide

- Status: Accepted
- Date: 2026-11-02

## Context

Standing is computed from encrypted events, so the server cannot see it and cannot enforce tiers. Hard enforcement would need per-tier keys.

## Decision

Clients compute tiers locally and show them. Tiers guide; owners decide. Access to an item's sensitive details follows the owner's approval, which wraps the item detail key to the borrower. Essentials are always borrowable. An owner may raise an item's minimum tier, never lower it below the class default.

## Consequences

Simple, honest model. A member cannot be technically stopped from asking; the owner declines. If circles later want hard enforcement, optional per-tier keys are a possible extension.
