# 0054. Hand-written wire JSON for the browser

- Status: Accepted
- Date: 2026-11-04

## Context

The bundle was 247 KB gzipped against a 250 KB budget (spec section 6, ADR 0009), and every new screen cost kilobytes. The analysis (`tools/bundle-analysis`) showed kotlinx-serialization's JSON support and the generated serializers of about a dozen small DTO classes at roughly 20 KB gzipped. The client sends and reads only a handful of shapes: an event, push and pull bodies, the login exchange, the stored identity and the export manifest.

## Decision

1. The browser path (`HttpSyncApi`, `IndexedDbReplica`, the export builder) reads and writes JSON with `circles.api.Wire`, over a small strict reader and writer in `circles.json` (whole numbers only, no duplicate keys, depth limit 32). Readers refuse unknown fields, missing fields and wrong types, as `ApiJson` does, and return null instead of throwing.
2. The server, the operator tools and the tests keep kotlinx-serialization (`ApiJson`). The DTO classes stay `@Serializable` in common code; the JS build drops what nothing reaches.
3. Agreement is tested, not assumed: `WireCompatTest` (JVM) checks byte-for-byte equal output with kotlinx for every shape the browser writes (including all control characters) and value-for-value equal reading of everything kotlinx writes, plus the refusals. `JsonTest` runs on both targets.
4. Changing a wire shape now means changing three places (the DTO, `Wire`, the test); that is the price of the saving.

## Consequences

- The bundle fell from 247.1 to 225.5 KB gzipped (21.6 KB), first visit on slow 3G from 6.3 s to 5.8 s.
- Stored identities written by earlier builds still read, because the bytes are identical.
- `ExportVerifier` still decodes with kotlinx; it is not used by the browser.
