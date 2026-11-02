# 0021. Sealed envelope `v = 1`, circle keys and key wrapping

- Status: Accepted
- Date: 2026-11-02

## Context

M2 must make the server store only ciphertext for private payloads (spec 3.2), retire the plaintext `v = 0` development format (ADR 0014), and define how the circle key reaches devices (spec 3.2 `key.wrap`, `key.rotate`; spec 5). Two details in the spec need fixing: the associated data includes the nonce in spirit but the nonce is only known after encryption, and "epoch 0 for bootstrap" does not say which epoch the first circle key has.

## Decision

1. **`v = 1` payloads.** `encrypted` is XChaCha20-Poly1305 (IETF) ciphertext plus tag under the circle key of `epoch`; `nonce` is the 24-byte random nonce. An event with no secret part has empty `nonce` and `encrypted`. `Cipher.encrypt` returns `nonce || ciphertext || tag`; the author splits it into the two envelope fields.
2. **Associated data** is `"circles/event-aad/v1"` followed by the canonical CBOR map of every envelope field except `nonce`, `encrypted` and `sig`. This amends spec 3.2, which excludes only `encrypted` and `sig`: the nonce cannot be in its own associated data. The nonce is still authenticated, because decryption fails under a different nonce, and it is covered by the signature.
3. **Circle keys.** A circle key is 32 random bytes. The first key is epoch 1, made by the founder and wrapped to the founder's own device with a `key.wrap` straight after `circle.create`. Epoch 0 means "no circle key": events with an empty `encrypted` use epoch 0, except `key.wrap` (the epoch it wraps) and `key.rotate` (the new epoch). An event with a non-empty `encrypted` uses the author's current epoch.
4. **`key.wrap`.** `public` is `{device, epoch, wrapped}`; `envelope.epoch` must equal `public.epoch`. `wrapped` is a sealed box, to the target device's X25519 `boxPub`, of the CBOR map `{circle, epoch, key}`. The receiver rejects a wrap whose circle or epoch differs from the event's, so an operator cannot replay an old wrap as a new one. Any member device that holds the key may wrap it for another registered device of the circle.
5. **`key.rotate`.** A steward sends `public = {epoch}` with `envelope.epoch` equal, where the value is the current epoch plus one. The new key is then wrapped to every current, non-revoked device with `key.wrap` events. Old epochs stay readable.
6. **Device box keys** are real X25519 keys made by `Cipher.generateBoxKeyPair`. A device keeps its box secret key next to its signing seed.
7. **Server.** The server accepts `v = 1`; it accepts `v = 0` only if `CIRCLES_ALLOW_PLAINTEXT_DEV=true` (default false). It stores each circle's current epoch (migration V2). It accepts the first `key.wrap` of epoch 1 from a steward, a `key.rotate` that is current plus one from a steward, and a `key.wrap` for an existing epoch to a registered, non-revoked device of the circle. It cannot check that a wrapped key is correct; clients do.
8. Done test for M2: a database dump of a circle with notes, labels and wrapped keys contains none of the plaintext.

## Consequences

- The operator still sees who is wrapped to which epoch, epochs and timing, as spec 3.2 already says.
- A device that joins sees nothing readable until another device wraps the key to it; the UI must show "waiting for keys".
- A malicious member who holds the key can wrap it to any registered device, including a device they added; the circle's trust model, not cryptography, limits that.
- Changing the associated data, key map or wrap format needs a new ADR and new test vectors.
- `v = 0` data is a development format and is not migrated.
