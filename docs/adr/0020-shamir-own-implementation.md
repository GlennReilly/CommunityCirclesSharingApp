# 0020. Shamir secret sharing: our own small implementation with independent vectors

- Status: Accepted
- Date: 2026-11-02

## Context

Spec section 5 splits the 32-byte circle key k-of-n with Shamir over GF(256) and asks us to either write it in `shared` with test vectors or use a vetted library. It must run identically on the JVM and in the browser. There is no widely used, maintained Kotlin Multiplatform Shamir library; using separate JVM and JS libraries would give two implementations that may disagree on share format, and each adds a dependency to audit. The algorithm is small (field multiplication, polynomial evaluation, Lagrange interpolation at 0). A mistake is serious, so the answer must be proven by tests rather than trusted.

## Decision

Implement it in common Kotlin in `circles.recovery` (no `java.*`):

- Field: GF(2^8) with the AES polynomial 0x11B, multiplication by a fixed-iteration shift-and-xor loop with no secret-dependent branches or table indexes.
- Each secret byte gets its own random polynomial of degree k-1 whose constant term is that byte; coefficients come from `Crypto.randomBytes`. Share x-coordinates are 1..n (n at most 255, k at least 2, k at most n); x = 0 is never used.
- Share format: `version(1) || k(1) || x(1) || y(32)`. A reconstruction needs at least k shares with distinct x and the same k and length.
- Integrity: the sealed result carries a BLAKE2b-256 check of the secret, held with the circle metadata (not in the share), so a wrong or corrupted set of shares is detected and reported as a sealed `Failure`, never a wrong key silently.
- Tests: field axioms and the AES multiplication vectors (0x57 * 0x83 = 0xC1); every k-subset of an n-share set reconstructs the secret (exhaustive for small n, k); any k-1 shares reconstruct nothing equal to the secret and give uniformly different results across runs; duplicates, mixed k and short sets are refused; known-answer vectors produced by an independent Python implementation (not the code under test), checked on both JVM and JS.

## Consequences

- No new dependency, one implementation for both targets, a share format we control.
- We own the correctness proof: the exhaustive subset tests and the independent vectors are mandatory, and a change to the field or format needs a new ADR and new vectors.
- Shares are not authenticated by themselves; the secret-hash check covers corruption but a malicious holder can still withhold or submit a bad share (detected, not prevented).
- Recovery word encoding (the 24-word code, a separate open decision) is unrelated and not decided here.
