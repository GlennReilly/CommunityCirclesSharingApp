# 0007. Integer days and rounded points in policy code

- Status: Accepted
- Date: 2026-11-02

## Context

The same policy code runs on the JVM and in the browser, and every device must reach the same tier from the same events. `java.time` and `String.format` do not exist in Kotlin/JS. Floating-point `pow` can differ in the last bit between platforms, and `kotlin.math.round` rounds ties to even on the JVM but up in JavaScript.

## Decision

Policy code uses integer days (the envelope already carries a day number), no JVM-only APIs, points rounded to two decimals before any comparison, and a tie-safe `floor(x + 0.5)` for rounding and formatting.

## Consequences

Deterministic results across platforms. The tests run on both targets to keep it that way. Sub-day precision is deliberately not available.
