# 0067. Tier names are New, Regular and Trusted

- Status: Accepted
- Date: 2026-11-06

## Context

The access tiers were named NEWCOMER, NEIGHBOUR, TRUSTED (with STEWARD as the top, advisory tier; the steward role itself comes from `steward.add`). Lendger is used by community groups of every kind, not only by people who live next to each other, so "neighbour" describes the wrong thing and "newcomer" reads oddly next to it. The owner decided that no tier name, label or message may say "neighbour".

The names are not only shown to people. They are Kotlin identifiers (`Tier`, `CircleConfig.vouchesForNeighbour`, `ConfigSettings.neighbourTenths`, `ContributionKind.MENTORED_NEWCOMER`), and some of them travel inside sealed payloads: `item.upsert` carries `minTier` as the enum name, `config.update` carries `neighbourTenths` and `vouchesForNeighbour` as CBOR map keys, and `standing.note` carries the contribution kind by name. No public (server-readable) field carries a tier or config name; the server never sees them.

No circle is in use yet: the pilot has not started.

## Decision

The tiers are **NEW, REGULAR, TRUSTED** (shown as "New", "Regular", "Trusted"); STEWARD stays as it is. Everything follows:

- `Tier.NEWCOMER` -> `Tier.NEW`, `Tier.NEIGHBOUR` -> `Tier.REGULAR`.
- `CircleConfig.vouchesForNeighbour` -> `vouchesForRegular`; `ConfigSettings.neighbourTenths` -> `regularTenths`, `vouchesForNeighbour` -> `vouchesForRegular`; `MIN_/MAX_VOUCHES_FOR_NEIGHBOUR` -> `MIN_/MAX_VOUCHES_FOR_REGULAR`; the default threshold constant likewise.
- `EventKind.MENTORED_NEWCOMER` and `ContributionKind.MENTORED_NEWCOMER` -> `MENTORED_NEW_MEMBER`.
- Messages say "new member", "Regular", "members (Regular or above)" instead of "newcomer" and "neighbours". The Members page reads the number of vouches from the circle's rules instead of saying "Two".
- The web client's set of signed-out pages, `Screen.newcomer`, is now `Screen.signedOut` (it never meant the tier).

**Clean break.** Sealed payloads change with the code, with no reader for the old names: `item.upsert.minTier` is `NEW`/`REGULAR`/`TRUSTED`, `config.update` uses the keys `regularTenths` and `vouchesForRegular`, and `standing.note.kind` uses `MENTORED_NEW_MEMBER`. A log written by an earlier build that holds one of the old values folds with that event rejected as malformed. This is acceptable only because no live circle exists; once a pilot starts, such a change needs a reader for both forms.

## Consequences

- The words people see match how Lendger is used.
- Circles made by development builds before this change may show rejected events: recreate them.
- Accepted ADRs keep their original wording as history and are not edited: ADR 0042 (standing and vouches), 0051 (config events, "vouches needed for Neighbour"), 0053 (contribution notes, `MENTORED_NEWCOMER`) and 0055 (skills, "newcomers", "Neighbour tier") use the old names; read NEWCOMER as NEW and NEIGHBOUR as REGULAR there. The original policy draft `docs/spec/CircleAccess.v0.1.kt.orig` is kept unchanged for the same reason. The design doc may still mention neighbours as one example of a group that could use Lendger.
