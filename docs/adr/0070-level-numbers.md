# 0070. People see level numbers, not tier names

- Status: Accepted
- Date: 2026-11-06
- Supersedes: the display wording of 0067 ("shown as New, Regular, Trusted"); its identifiers stay

## Context

ADR 0067 named the access tiers New, Regular and Trusted and showed those words to people. The owner decided: "Let's just have level numbers, level 1 for new people, level 2 for more experienced users etc." Numbers say plainly that there is an order and what comes next, and no word can describe the wrong kind of group.

Two problems were found while looking at the wording. Several messages built from the policy library printed the Kotlin enum name directly, for example "Cordless drill needs REGULAR; you are NEW" and "Or get 2 members of tier REGULAR or above to vouch for you"; others used `name.lowercase()` ("Stewards are treated as at least trusted"). The tier words were spread over the library, the fold, the app layer and the web views, each with its own spelling.

The tiers are also identifiers. `Tier.NEW/REGULAR/TRUSTED/STEWARD` are Kotlin names, `item.upsert.minTier` carries the enum name inside a sealed payload, `config.update` uses the CBOR keys `regularTenths` and `vouchesForRegular`, and the web item form submits the enum name as the option value. ADR 0067 already made one clean break for the names; a pilot is getting closer.

## Decision

1. **What people see.** Each tier is shown as a level number: `NEW` is **Level 1**, `REGULAR` **Level 2**, `TRUSTED` **Level 3**. Every screen, message and reason says "Level N", "Level N or above", "Your level: N". The steward role stays a role with its own name, and stewards still count as at least Level 3 (ADR 0042 A1).
2. **The advisory top tier.** `STEWARD` as a tier (reached by points, `stewardTenths`, default 25; the role itself comes from `steward.add`) is shown as **Level 4**, continuing the numbers ("etc."). It opens nothing that Level 3 does not; the standing page says so. The circle rules read "Points for Level 2, Level 3 and Level 4". This reading of "etc." is ours: the owner may prefer to hide Level 4 (show Level 3) or name it; changing it is one function.
3. **One function.** `circles.policy.TierText` (common code) is the only place a tier becomes words: `number(tier)` (1 to 4), `label(tier)` ("Level 2") and `orAbove(tier)` ("Level 2 or above"). The policy library (access reasons, how-to-unlock, pause refusals), the standing rules (steward floor), the fold's refusal reasons, the app layer (unlock hints) and the web views all call it. No user-facing string may interpolate a `Tier` or its `name`.
4. **Identifiers do not change.** `Tier.NEW/REGULAR/TRUSTED/STEWARD`, `regularTenths`, `trustedTenths`, `stewardTenths`, `vouchesForRegular`, `MENTORED_NEW_MEMBER`, the `minTier` values in `item.upsert` and the web form's option values keep their names. This is wording only: no sealed payload, wire field or stored value changes, so there is no second clean break and no reader for old forms is needed.
5. **Tests.** `TierTextTest` (common, JVM and JS) checks every label and runs every code path that produces tier text (eligible and not-yet access decisions for each needed tier, the vouch route, the vouch reason, the steward floor, the pause refusal, the fold's vouch, name-confirmation and pause refusals) and fails if any output contains an enum name (`NEW`, `REGULAR`, `TRUSTED`, `STEWARD`) or an old label (`Regular`, `Trusted`, `Steward level`). `TierWordsSourceScanTest` (JVM) scans the string literals of `shared/src/commonMain` and `web/src/jsMain` for the old labels and for templates that interpolate a tier value directly.

## Consequences

- People read an order they can follow ("needs Level 2; you are Level 1") and never see an enum name.
- Developers keep reading NEW/REGULAR/TRUSTED in code, logs and sealed payloads; the mapping is `rank + 1`. Specs that describe identifiers keep the identifiers and say how they are shown.
- Adding or renaming a tier later means changing `TierText` and its test; the scan test catches a new template that bypasses it.
- Accepted ADRs keep their words as history (0042, 0051, 0053, 0055, 0067, 0068 say Regular or Trusted; read Level 2 and Level 3).
