# 0048. Recovery shares in the app

- Status: Accepted
- Date: 2026-11-03

## Context

`recovery.share` (spec 3.3, ADR 0020) splits a circle key k-of-n, one share sealed to each steward device, and the server and fold already accept it. Nothing in the app makes or uses one. Handover test 5 says any 2 of 3 stewards can rebuild the circle key from shares.

A share sealed to a steward's device is only useful if that device survives, and a surviving steward device already holds the key itself. The shares earn their place in the case where every device that held the key is gone, or where the stewards who hold it cannot be reached one by one: the stewards then bring shares together, in person or by phone, and one of them (on a fresh or recovery-code device registered in the log) rebuilds the key and wraps it to their own device. ADR 0045's key commitment lets the rebuilt key be checked against the log before it is used.

## Decision

1. **Making shares is automatic** (decision C2, as the owner chose). The circle has a fifth steward setting, `recoveryThreshold` k (0 = no recovery shares, the default; otherwise 2 up to the number of stewards), set in `circle.create` and changed by `steward.policy` and its votes like the other three settings (ADR 0027), with the subject string gaining an optional fourth part so older proposals still parse. Whenever k is set and the epoch changes (`key.rotate`) or the steward list changes, the steward device that made the change sends fresh shares for the current epoch in the same batch. If a steward device finds on refresh that the newest shares are older than the current epoch or steward list (the change was made by a client that did not send them), it sends them. Duplicate sets are harmless: the fold keeps the newest set per steward and epoch. The Stewards page shows the setting, whether the latest shares are current, and a button to make them now.
2. **One share per steward, sealed to each of that steward's live devices** (decision A): the same share, one event per device, so a steward who loses one device still has it and a steward with two devices does not count twice.
3. **Using shares is by hand** (decision B): each steward opens their share on the same page, which shows it as a short text code with a checksum (and its epoch and k) to read out or send privately. The steward who is recovering pastes k codes into "Rebuild the key"; the app combines them, checks the result against the epoch's key commitment, and wraps the key to the steward's own device with `key.wrap`. A wrong, mixed or too few set is explained and nothing is sent.
4. **No new events and no server change.** Everything uses `recovery.share` and `key.wrap` as they are.
5. **Honest limits.** A share code is a secret: whoever holds k of them holds the circle key, so the page says to give it to the recovering steward only, in person or by a call. A steward who loses all devices and has no recovery code (ADR 0047) cannot open their share either. Stewards who are not reachable cannot be forced.

## Decisions for the owner (answered 2026-11-03: A1, B1, C2)

**A. Share holders.** **A1 (chosen): one share per steward, sealed to all their live devices.** **A2: one share per device** (the primitive as it is): simpler, but a two-device steward could supply two shares.

**B. Using shares. B1 (recommended): by hand** as in point 3: no new events, nothing to trust but the stewards' own words. **B2: in the app**: the recovering steward posts a request and each steward answers with their share sealed to the new device. Smoother, but it needs new event types and a server change, and a share would then travel through the log.

**C. When shares are made. Chosen: C2.** **C1: by a steward's choice**, with a prompt when they are out of date. **C2 (chosen): automatically after every rotation or steward change**, using a threshold stored in the circle's steward settings (a new setting, voted like the others).

## Consequences

- Handover test 5 becomes something a person can do in the app, and has a browser-script check.
- The setting touches the public parts of `circle.create` and `steward.policy`, server migration V7 (a `circles` column), the server's validation, `StewardSettings`/`StewardPolicy`, the fold, the export format check and the Stewards page. It is built in two slices: first the share machinery (fan-out per steward, the fold keeping the shares a device holds, share codes, "Rebuild the key", manual "make now"), then the setting and the automatic sending.
- Automatic sending means a steward's client does extra work after rotations; if a steward is offline, the next steward device to refresh makes the shares, so they are never stale for long.
- A1 means the share-making code takes one share per steward and fans it out to devices; the fold keeps one share per steward per epoch.
