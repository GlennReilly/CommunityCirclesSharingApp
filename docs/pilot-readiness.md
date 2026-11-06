# Before a real pilot (written 2026-11-04, refreshed 2026-11-05)

What is left between today's build and a pilot with one real group of 10 to 20 people (spec M6). Sizes: S = under a day, M = a few days, L = a week or more of work. "You" means the project owner.

## Works today (tested)

Sign-in and passphrase keys, circles, invites, second devices, recovery code, items with private details, borrowing (request, approve, hand over, return, confirm, dispute, resolve), standing, vouching, stewards and their rules, member removal with key and item-key changes, pauses, recovery shares, export and import, backup and restore, the install guides, a 300-member load test, 83 browser checks with axe, and a 229 to 236 KB bundle against 250 KB.

## Must do before the pilot

| Item | Size | Notes |
|---|---|---|
| Install on a real machine over HTTPS (Pi or small VPS) with a real certificate and systemd, following only `docs/operator/` | M | Never done; the guides were checked in containers and by `systemd-analyze verify` only. **You.** |
| A person who has not seen the code follows the operator guides | S | The definition of done asks for it. **You** to find the person. |
| Try every screen on a real phone (touch, small screen, slow network, Argon2 time) | S | Argon2 is 1.6 s on the old dev machine; re-tune on the slowest phone in the group. **You.** |
| Screen-reader pass of the borrow flow, and a reading-level pass of the text | M | Axe is clean, but a person using a screen reader must complete borrowing unaided. |
| ~~Skills screen~~ | done (ADR 0055) | Offers are a directory: no messaging, people talk outside the app. |
| ~~Loan windows: block overlapping requests, cancel a request~~ | done (ADR 0052) | The owner may approve other dates (ADR 0061); changing dates after approving is not built: the borrower cancels and asks again. |
| ~~Reminders for returns~~ | done (ADR 0059) | In the app only; email is left until the pilot shows it is needed. |

## Should do

| Item | Size | Notes |
|---|---|---|
| Config events (ADR 0051) and the owner's minimum tier on the item form | M | Lets the pilot tune thresholds without a release. |
| ~~Photos on items~~ | done (ADR 0060) | Up to 3 per item, thumbnails in the list; the browser export now carries the sealed photos (up to 200 MiB, any that cannot be fetched are counted in the notice). |
| ~~QR codes: member fingerprint check and item tags~~ | done (ADR 0062) | Read with the phone camera app; there is no scanner inside Lendger. |
| ~~`standing.note` contribution events~~ | done (ADR 0053) | The sponsor nudge for upheld damage disputes was already in (ADR 0043). |
| ~~Passkey (WebAuthn PRF) unlock~~ | done (ADR 0063) | Tried only with Chrome's virtual authenticator: try a real phone, laptop or security key. |
| ~~Appearance settings (text size, colours)~~ | done | Standard, large, largest text and light, dark or device colours, kept in the browser. Language is not offered: the pilot is English only. A contrast setting beyond light and dark is not built. |
| ~~Member names~~ | done (ADR 0068) | Chosen by each member, asked for on the first page after being let in; confirmed by a member at Level 2 or above or a steward who checked the fingerprint in person. A joiner has no name until let in (it cannot be sealed before), so stewards let people in by fingerprint. Contact preferences and area are not built. |
| ~~Pause history~~ | done | "Earlier pauses" on the Pauses page, only pauses that took effect, visible to the subject and the reviewers. A general "what happened" log of everything stewards did is not built. |

## Known limits to tell the pilot group

- A removed steward keeps their old recovery share until a new set is sent; shares are only refreshed when the first-sorted steward opens the app. Sets are told apart by a name each share carries (ADR 0065), so other activity no longer makes a set look incomplete.
- The script is capped at 300 KiB gzipped (ADR 0064); a first visit takes about 2 s on fast 3G and 6 s on slow 3G, once per version because the service worker keeps it.
- A borrower's new device cannot read details of items already approved until the owner approves again.
- Anyone who holds a member's passphrase and a device, or a recovery code, can act as that member.
- The server cannot read circle contents, but the operator can see who talks to whom and when.
