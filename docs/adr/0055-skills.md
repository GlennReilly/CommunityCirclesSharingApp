# 0055. Skill offers

- Status: Accepted
- Date: 2026-11-04

## Context

The design lists a skill offer as "a person offering help (carpentry, plumbing, pottery) with optional open-to terms (barter, gift, money)". The spec names `skill.upsert` and `skill.retire` (author: member) and a Skills screen whose search is local and client-side. Nothing else is defined. Skills are about people, so they raise questions items do not: who may see an offer, how much a member says about what they can do, and how an offer turns into a contact. The money-free ideals of the project (non-goals: currency, credits, payments) shape the "open to" terms.

## Decision

1. **Events** (sealed like items; `public` empty): `skill.upsert {skill id, name (1 to 60 chars), details (0 to 500), openTo (set of GIFT, SWAP, THANKS)}` and `skill.retire {skill id}`; only the author may change or retire their own. The skill id is random like an item id.
2. **Visibility.** Every member of the circle can see every offer, like items. No one outside the circle can; the server sees only sealed events.
3. **Open-to terms.** `GIFT` (happy to help for nothing), `SWAP` (would like a favour or a trade back), `THANKS` (a thank-you note is enough). No money term: the project's non-goals exclude currency, so "money" from the design is not offered as a setting; a member who wants to be paid is outside what this app arranges.
4. **Asking.** There is no request workflow. A "Ask for help" button on an offer fills a short message that goes through the circle's existing channel? Nothing exists for messages, so v1 shows the offerer's member short id and fingerprint and says "ask them in person or in your group chat". Contribution notes (ADR 0053) are how helping later counts towards standing.
5. **Search** is local: the screen lists offers sorted by name and has one text box that filters name and details as you type, ignoring case.
6. **Limits.** At most 10 live offers per member. Retired offers disappear.
7. **Access and standing.** Skills are open to every member including newcomers; offering a skill earns no standing by itself.

## Decisions for the owner (answered 2026-11-04: A1, B1, C1)

**A. Money term. A1 (recommended): no money setting**, as in point 3. **A2: add `PAID`** so a plumber can say they charge; this makes the app a small marketplace and invites the things the project excludes.

**B. Contacting. B1 (recommended): no messaging in v1**; the offer shows who and how to verify them, and people talk outside the app. **B2: a one-line "ask" message** sealed to the offerer (new event type, an inbox screen, notifications): much more to build and to protect.

**C. Who can see offers. C1 (recommended): every member.** **C2: Neighbour tier and above**, so a brand-new member cannot browse who can do what (a mild guard against someone joining only to find people to approach).

## Consequences

- Needs two payloads, fold rules, a Skills page (list, add and edit form, retire) and tests; the server accepts the events opaquely. About 3 to 4 KB of bundle, which now fits (225 KB of 250 KB).
- Without messaging (B1), the screen is a directory; the value is in finding who to ask.

## As built (2026-11-04)

`skill.upsert {skill, name, details, openTo}` (open-to is a bit set stored as a small integer: GIFT 1, SWAP 2, THANKS 4) and `skill.retire {skill}` are sealed events. `MarketFold` keeps `SkillState`; only the author changes or retires an offer, a retired one cannot return, a member has at most 10 live offers, and the author must be a member. The Skills page lists the offers of everyone still in the circle with the offerer's short id and fingerprint, filtered on the device as the search box changes (a hidden attribute on each row, no state). Add and change pages are sub pages. Bundle 230.3 KB gzipped.
