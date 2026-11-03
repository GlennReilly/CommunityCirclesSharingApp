# 0045. Circle key commitments

- Status: Proposed
- Date: 2026-11-03

## Context

ADR 0021 lets any member who holds the circle key wrap it to another registered device, so that a joiner or a new device can be let in without a steward. A device that opens a wrap cannot tell whether the key inside is the circle's real key for that epoch: it only checks that the wrap names the right circle and epoch. A hostile member can therefore wrap a different key to a device (before or after the honest wrap). That device then seals and opens with a key nobody else holds, or silently cannot read the circle, and has no way to notice. ADR 0044 recorded this and deferred the fix. The fix needs a place where the circle's real key for each epoch is committed to publicly, without revealing it.

## Decision (proposed)

1. **A commitment** is `keyHash = BLAKE2b-256("circles/key-commitment/v1" ‖ circle id ‖ epoch (4 bytes, big endian) ‖ key)`. The key is 256 random bits, so the hash tells nobody anything about it, and the circle id and epoch keep two commitments from ever being equal.
2. **Where it is published.** In a new public field `keyHash` (32 bytes) of the event that starts an epoch: the founder's first `key.wrap` (epoch 1), and each `key.rotate` (epoch N+1). Both are stewards-only already. Later `key.wrap`s of the same epoch (letting someone in) carry no hash.
3. **What a client does.** When it opens a wrap for an epoch it has a commitment for, it checks the hash of the unwrapped key. A wrap that does not match is ignored (it gives no key) and is listed in the debug list as rejected with the reason "wrapped key does not match the epoch's commitment". The first matching wrap gives the key. A device that is wrapped a wrong key is therefore no worse off than one not wrapped yet, and an honest wrap after it still works.
4. **Server.** It checks only the shape: `keyHash` is 32 bytes. It requires one when an epoch starts (the first wrap of epoch 1, and every `key.rotate`) so an old or careless client cannot start an epoch without one (see decision A). It cannot check that the hash belongs to the key; clients do.
5. **Builders.** `EventAuthor` gains `firstKeyWrap` (epoch 1, with the hash) and `rotateKeys` puts the hash in the `key.rotate`. The app uses them when it starts a circle and when it removes a member or rotates.
6. **Recovery shares** reuse the wrap shape for Shamir shares of the circle key; they carry no commitment and are not affected.

## Decision for the owner

**A. Is the commitment required or optional?** **A1 (recommended): required from now on.** The server refuses an epoch start without it, and a client treats a `key.rotate` or first wrap without one as invalid. No pilot circle exists yet, so the only cost is that circles made in earlier development builds stop working and must be recreated. It also stops a careless or hostile steward from starting an epoch without protection. **A2: optional.** An epoch without a commitment behaves as today. Old circles keep working, but anyone able to start an epoch (any steward) can leave it unprotected, and the protection depends on every client always including it.

## Consequences

- A hostile member can no longer give a device a wrong key; the worst they can do is send a wrap that is ignored.
- Event formats change: `key.wrap` (when it starts an epoch) and `key.rotate` gain one field, with test vectors from an independent encoder like the other formats.
- The commitment is public, so the operator learns nothing new: it already sees which events start epochs.
- A steward who starts an epoch with a commitment for a key they then give to nobody can lock the circle out; that is a steward's power and the quorum of ADR 0027 is the answer to it, not this field.
- Items' keys are not affected: an item key only reaches a device through its owner's own events.
