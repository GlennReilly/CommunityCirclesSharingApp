# 0044. Removing a member and rotating the circle key

- Status: Accepted
- Date: 2026-11-03

## Context

A circle has no way to remove anyone yet. The pieces exist but are not joined up: `device.revoke` (a member may revoke their own devices, a steward any device), `key.rotate` and `key.wrap` (ADR 0021), and `EventAuthor.rotateKeys`. Spec 5 says a key rotation is required after a revoke or a member leaving.

Revoking a device stops it logging in and syncing, but it keeps every key it already holds. If it (or someone with a copy of its keys, or a hostile operator) obtains later ciphertext, an unrotated circle key still opens it. Rotation closes that: events after the rotation are sealed with a key the removed device never receives. It cannot take back what the member already read (old epochs stay readable, ADR 0021), and it does not change item detail keys that were wrapped to their devices.

One thing found while reading the rules, which this ADR fixes: **a hole in steward governance.** The server (and so every honest client) lets any steward revoke any member's device. Steward changes need a quorum (ADR 0027), but a single steward could instead revoke another steward's devices and lock them out, bypassing it.

One thing found that this ADR records but does not fix: **a member can wrap a wrong key.** Any member holding the key may wrap it to a device (ADR 0021), and a device takes the last wrap it sees for an epoch, so a hostile member could make a device hold a key the others do not. Atomic batches and the common log order keep honest rotations consistent, but the real fix is a key commitment (a hash of each epoch's key in a public field, so a wrong wrap can be told apart and ignored). That changes event formats and gets its own ADR.

## Decision (proposed)

1. **Remove a member** is one action by a steward's client, sent as one batch: a `device.revoke` for every live device of the member, then `key.rotate` for the next epoch, then a `key.wrap` of the new key to every remaining live device (including the remover's), using `rotateKeys`. A batch is processed in order by the server, so two removals cannot interleave their events.
2. **Stewards cannot be removed as members.** A steward's devices may be revoked only by that steward. To remove a steward, first remove them as a steward by the quorum of ADR 0027, then remove them as a member. The server and the fold both enforce it.
3. **Rotations are batches.** Because the server processes a pushed batch in order and every client sees the same log order, two stewards rotating at nearly the same time give every device the same final key. (Making a wrong wrap detectable is the separate key-commitment work above.)
4. **What a removed member leaves behind.** Their items stop being listed and cannot be requested; loans already made stay as history and can still be returned; their vouches stop counting from the day they were removed; their past standing events are kept. Items they had borrowed keep their owners' detail keys: owners should change the key (a later change; the Members page says so).
5. **The removed member's own screen.** When a device cannot sign in any more it says so in plain words ("Cannot sign in to the circle. If you were removed, ask a steward.") and offers "Forget this device".
6. **Screens.** Members: a steward sees "Remove from the circle" (behind a disclosure with a warning) for members who are not stewards, with the rotation done for them. Settings: "Leave the circle" revokes your own devices.

## Decisions for the owner (answered 2026-11-03: A1, B1, C1)

**A. Who may remove a member. Chosen: A1.** **A1 (recommended): any single steward**, as the server rules already allow, with point 2 closing the loophole for stewards. Quick, and a wrongly removed person can be invited again. **A2: a quorum of stewards** (like steward changes). Safer against one rogue steward, but needs a new voted event and the server would still revoke at the first `device.revoke`, so it is a bigger change.

**B. Members leaving on their own. Chosen: B1.** **B1 (recommended):** a member may leave by revoking their own devices (Settings, "Leave the circle"); every steward then sees a prompt on the Members page ("Alex left: rotate the key now"), and any steward's rotation clears it. **B2:** leaving is not self-service; the member asks a steward, who removes them as in point 1 (always rotates at once).

**C. Their vouches. Chosen: C1.** **C1 (recommended): they stop counting from the removal day**, as in point 4: vouching is a statement by someone who is no longer part of the circle. **C2:** they keep counting until they expire.

## Details settled while building

- A removal day is the latest day on which one of the member's devices was revoked, once all of them are. Vouches made by a removed member stop counting for any decision dated on or after that day.
- After a member leaves on their own, stewards see "Rotate the key" on the Members page until a rotation follows the revocation in the log.
- A member who leaves forgets their local data on that device once the revocation has been sent.
- Items of a member with no live devices are not listed and cannot be requested by the app.

## Consequences

- A stolen or lost device, or an unwanted member, can be cut off for new events with one action.
- The cost of a rotation is one wrap per remaining device (a few hundred small events for a large circle), sent at once; members who are offline pick it up on their next sync.
- Old events stay readable to the removed device if it kept them; the screen says so.
- Closing the steward loophole is a behaviour change for the server and the fold; existing tests that let a steward revoke another steward are updated.
- A steward who is the member leaving must step down as a steward first; the app says so.
- Not covered: rotating item detail keys after a removal, banning a removed member from rejoining (an invite still works), and removal by quorum.
