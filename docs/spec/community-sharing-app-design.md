# Community sharing network: design doc ("Lendger")

Status: draft v0.2, 2026-11-02 (v0.2: `public`/`encrypted` envelope split, optimistic returns, corrected "what the server sees", policy corrections)
Owner: Glenn
Companion doc: `community-sharing-app-spec.md` (implementation spec for Claude Code)
Reference code: `CircleAccess.kt` (trust model v0.2, now in `shared/src/commonMain/kotlin/circles/policy/`, tested on JVM and JS)

## 1. Purpose

A local-first web app that lets groups of neighbours, friends or communities share tools, skills and help without using money, credit scores or a central company. It grew out of a r/solarpunk thread on trading outside standard monetary systems, where the strongest practical idea was a lending and skill-share network rather than a new currency.

It is a hobby and community project. It must work whether or not its author ever hosts an instance.

## 2. Principles

1. **Sharing network, not a currency.** No balances, no debts, no interest.
2. **Non-punitive trust.** Access widens with contribution and fades gently. Nobody is "banned"; the floor is "welcome, limited". Essentials are always open.
3. **Local trust.** Standing is computed per circle. There is no global reputation.
4. **Local-first.** Every device keeps a usable replica and works offline. The server is a hub, not the owner of the data.
5. **Host-agnostic.** One small deployable unit, no external services, great operator documentation. Nothing depends on a particular host, including the author.
6. **Handover must work.** If the person running an instance leaves, the group can recover on a new host without that person and without losing history.
7. **Minimal data.** Collect and retain only what the model needs. Location stays coarse.
8. **Accessible.** Low-end phones, patchy connections, screen readers, plain language, and a path for people who will not use an app.

## 3. What we learned from the source thread

- The anthropology (Graeber, *Debt*) says early economies ran on credit and obligation, not barter. Obligation is a social fact before it is an accounting fact.
- Working precedents exist: LETS and time banks, WIR (Switzerland, since 1934), Sardex (Sardinia), Grassroots Economics community currencies (Kenya), tool and seed libraries.
- Failure modes are consistent: thin liquidity, no way to pay real bills, volunteer burnout, and opaque or punitive scorekeeping.
- The commenter building a friend-network lending app had the right instincts: no public search, logistics kept offline, nothing valuable stored centrally.
- Legal note (Australia): the ATO treats barter as taxable at market value, so anything that looks like trade of services for goods at scale needs care. Lending and gifting are different. This app stays on the lending and gifting side.

## 4. Concepts

- **Circle**: a group (a street, a club, a village). Each circle has its own config, members and history.
- **Member**: a person in a circle, with one or more devices.
- **Item**: something shared (tool, equipment, space). Classes: ESSENTIAL, EVERYDAY, VALUABLE, HIGH_VALUE.
- **Skill offer**: a person offering help (carpentry, plumbing, pottery) with optional open-to terms (barter, gift, money).
- **Tiers**: NEW, REGULAR, TRUSTED, STEWARD (names per ADR 0067). Derived, never stored.
- **Standing**: decayed sum of contribution events, clamped at zero, per circle.
- **Vouch**: a member of sufficient tier vouches for a new member; two live vouches open REGULAR access. Vouches expire. A serious incident gives sponsors a mild nudge.
- **Pause**: last resort. Needs several distinct reviewers who are themselves trusted members, a reason, an expiry and an appeal route. Essentials stay open during a pause.
- **Incident cooldown**: after an unreported damage or loss, higher-value items are on hold for a while; essentials and everyday items stay open. Standing points are clamped at zero, so without this a low-standing member would face no consequence at all.
- **Optimistic return**: a borrower's report that an item came back counts after a short grace period unless the owner disputes it. Owners are never penalised for not confirming (they are the scarce resource); a prompt confirmation earns a small bonus. Disputes go to a steward.
- **Steward**: a member with administrative duties (key recovery, config, pause review). A circle should have at least three.

Tiers guide; **owners decide**. A tier is advice shown to the owner and the borrower, not a hard gate, because the server cannot see standing (see section 7).

## 5. Architecture

```
 Browser PWA (installable)                    Circle server (one per group)
 +--------------------------+                 +----------------------------+
 | UI (Preact/TS)           |                 | Ktor (JVM)                 |
 | shared Kotlin logic (JS) |  HTTPS push/pull| auth, sync, blobs, static  |
 | local replica (IndexedDB)| <-------------> | SQLite (one data dir)      |
 | outbox, keys (device)    |                 | stores ciphertext+envelope |
 +--------------------------+                 +----------------------------+
```

- **Shared Kotlin Multiplatform module** holds the model, canonical encoding, event validation, policy (tiers, decay, access) and sync-client logic. It compiles to JVM (server, tests) and JS (browser). The same rules run on both sides.
- **Server** is a Ktor app with SQLite. It validates signatures, assigns a per-circle sequence number, stores ciphertext and plaintext envelope metadata, and serves the PWA. It holds no member keys.
- **PWA** is the primary client: installable, offline-capable, no app store.
- **Optional later**: an Android wrapper, which would reuse the shared module.

## 6. Data model: an append-only event log

Everything that changes state is an event. Current state (items, loans, standing, tiers) is derived by folding the log. Consequences:

- Sync is "push my new events, pull events after cursor N". Events are idempotent (content-addressed id), so duplicates are harmless.
- No CRDT machinery is needed. Merging replicas is set union.
- The log is the backup. It is also the export format.
- Auditing is natural: decisions come with the events that caused them.

Each event is an **envelope** (plaintext metadata plus signature) around two parts: a small `public` section (signed but readable by the server) and an `encrypted` section (the secret part). Envelope fields are the minimum the server needs: ids, circle, author, coarse type, rounded date, key epoch, and the signature. `public` holds only fields the server must read to enforce rules (device keys, steward changes, the invite key, wrapped keys), or that must be readable before a member holds the circle key. Everything else is in `encrypted`. Details are in the spec (3.2).

Conflict handling:
- Two people request the same item offline: both requests are valid events; the owner's `loan.respond` decides. Clients show "pending" until then.
- Concurrent edits to an item: last writer by server sequence wins for each field group; history is retained.
- Clock skew: event dates are claims. The server rejects future dates beyond a day and clients clamp when folding.

## 7. Cryptography and privacy

Goal: the server operator, a hosting provider or an attacker with a database dump cannot read item details, loan details, standing events or notes. They can see metadata (see below).

Key hierarchy:
- **Device keys**: Ed25519 signing key and X25519 key per device, generated on the device. Members register devices through signed `device.add` events.
- **Circle key**: a 256-bit symmetric key (XChaCha20-Poly1305) per epoch. Wrapped to each member device (sealed box). Rotated when a member leaves or a device is revoked.
- **Per-item detail key**: item public fields (name, class, photo) use the circle key. Sensitive details (exact address, contact, access notes) use a per-item detail key that the owner wraps only to approved borrowers on `loan.respond`. Owner consent becomes cryptographic access.

Why tiers are advisory: the server never sees standing events, so it cannot enforce tiers. Clients compute tiers locally and show them. Owners make the real decision, and the keys to sensitive details follow that decision.

What the server still sees: circle membership, the steward set, device registrations and which device is wrapped to which key epoch, the author, type and day of each event, and approximate payload sizes. It does not see who vouches for whom, because vouch targets are inside `encrypted`. Server-side checks (roles, device keys) are hygiene against spam, not security: the operator is not trusted, and clients re-validate every event. We document this plainly so groups can decide whether they trust their host.

Not defended against: a malicious operator who adds a fake member and gets circle keys wrapped to them. Mitigation: members verify new members in person (QR or short fingerprint comparison) before a steward wraps keys.

Location: coarse by default (suburb or street), shown only to approved borrowers.

## 8. Hosting, operations and handover

Host-agnostic means:
- One container image and one runnable jar. No external database, queue or email service required.
- All state in one data directory. Backup is a consistent copy of it.
- Configuration through environment variables.
- HTTPS is required (service workers and passkeys need a secure context). The docs cover a reverse proxy (Caddy first) and later built-in certificate handling.
- Built-in backup, restore, export and import commands; automatic backup before migrations.
- Health endpoints, structured logs, visible version.

**Handover guarantees** (this is a first-class feature, with acceptance tests):
1. **Restore from backup onto a new host.** Members reconnect and sign in with their existing device keys. No re-enrolment, no old-host credential involved. Authentication is challenge-response against public keys recorded in the log, so it does not depend on the host.
2. **Restore with no backup.** Any member device holds a replica. A steward can export from that replica and import it into a new host. The log plus the members' keys is the entire system.
3. **Steward leaves.** Remaining stewards remove the steward and rotate the circle key.
4. **Lost device.** A steward, after in-person verification, wraps the current circle key to the member's new device. Optionally the member's recovery code does the same.
5. **Circle-key recovery.** Circle key epochs are Shamir-split k-of-n across stewards (default 2 of 3) and stored as wrapped shares. No single steward, and not the host, can lose the group's data alone.

## 9. Federation (deferred)

Standing is local and stays local. If federation arrives, it is thin:
- Share **discovery** only (skill directories, opted-in public listings) across instances.
- Cross-circle vouching as signed attestations that a receiving circle may accept or ignore.
- ActivityPub is a candidate for public discovery but is weak for private inventory, so it would not carry circle data.

v1 prepares by using stable ids and signed events. Nothing else.

## 10. Accessibility and inclusion

- WCAG 2.2 AA target: keyboard navigation, screen-reader labels, sufficient contrast, reduced motion, scalable text.
- Plain-language copy; i18n scaffolding from the start.
- Performance budget for low-end phones and slow connections (see spec).
- Notifications via email or SMS where the host configures it; never push-only.
- Coordinator mode: a steward or helper acts on behalf of a member who will not use an app.
- Printable QR tags on items to log borrow and return, including offline.

## 11. Risks and open questions

| Risk or question | Notes |
|---|---|
| Key loss is the main accessibility risk | Steward-assisted rewrap and recovery code; test with non-technical users early |
| Safari and iOS PWA limits | No Background Sync; sync on open and on connectivity events; verify WebAuthn behaviour |
| Self-reported events can be gamed | Loans need two-party approval and hand-over; returns are optimistic but disputable; low weights and a per-counterpart credit cap limit gain from rings of accomplices |
| Tiers are advisory | Accepted trade-off; revisit per-tier keys if circles want hard enforcement |
| Insider entrenchment | Decay, vouch expiry, and low barriers to REGULAR reduce it; monitor in practice |
| Legal (tax, liability for lent items) | Add a plain-language note; consider a "lend at your own risk" notice per circle |
| Crypto implementation risk | Use libsodium only; no custom primitives; get a review before wider rollout |
| Kotlin/JS size and maturity | Measure bundle size early; fall back to TypeScript for the thin UI layer only |

## 12. Roadmap

- **M0** Repo skeleton, CI, port and test the policy module.
- **M1** Event log, signed envelopes, single-circle server, sync.
- **M2** Encryption: circle key, payload encryption, key wrapping.
- **M3** PWA with offline replica, outbox and core screens.
- **M4** Invites, vouching, stewards, pauses, key rotation, recovery.
- **M5** Operations: backup, restore, export, import, docs, deployment recipes; run the handover acceptance tests.
- **M6** Accessibility audit and a pilot with a real group.
- **Later** Discovery federation; Android wrapper; optional per-tier keys.
