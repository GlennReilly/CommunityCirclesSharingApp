# 0012. Canonical encoding, event identity and wire format

- Status: Accepted
- Date: 2026-11-02

## Context

Signatures and ids need one byte string per event on every platform. The spec says "deterministic CBOR" but also hashes different things in sections 2 and 3.1 ("canonical bytes of the signed envelope fields" versus "canonical bytes including sig"), and it does not say how an envelope travels as JSON.

## Decision

**Encoding.** A hand-written CBOR encoder and decoder in `shared` (common Kotlin, no `java.*`) for this subset only: unsigned and negative integers within the 64-bit signed range, byte strings, text strings, arrays, maps with text keys, `true`, `false`, `null`. Not supported: floats, tags, indefinite lengths, non-text map keys. The encoder follows RFC 8949 section 4.2: shortest-form integers and lengths, map keys sorted bytewise by their encoded form. The decoder is strict: it rejects anything the encoder would not have produced (non-shortest forms, unsorted or duplicate keys, trailing bytes, invalid UTF-8, nesting deeper than 16), so each event has exactly one valid byte string.

**Envelope bytes.** A CBOR map with text keys `v, circle, author, device, type, day, ctr, epoch, public, nonce, encrypted, sig`. `public` is a nested map. Signing input = ASCII `"circles/event/v1"` followed by the CBOR of the map without `sig`. `id` = lowercase hex of BLAKE2b-256 over `"circles/event/v1"` followed by the CBOR of the map with `sig`. The domain string names the envelope layout; the `v` field is the payload version (ADR 0014).

**Wire format.** JSON objects with the same field names; byte fields are base64url without padding; `public` is the base64url of its canonical CBOR, carried verbatim so no JSON-to-CBOR conversion can change signed bytes. The server rebuilds the envelope, requires `public` to be canonical, and recomputes the id.

## Consequences

Shared test vectors (hex) pin the encoding on JVM and JS. Floats can never appear in an event. Adding a type to the subset later changes what clients accept, so it needs a new ADR. Because the decoder is strict, a client with a different CBOR library must produce canonical bytes or the server rejects the event as malformed.
