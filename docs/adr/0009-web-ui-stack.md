# 0009. Web UI stack

- Status: Proposed
- Date: 2026-11-02

## Context

The spec proposes a Vite + TypeScript + Preact PWA consuming the `shared` module through a narrow Kotlin/JS facade, for bundle size and accessibility control. The project default is Kotlin for all suitable work, and a TypeScript UI adds a second toolchain and a Gradle/Vite integration to maintain.

## Decision

Not yet decided. M0 only builds the `shared` module for JS (Node tests). Before M3, spike both options, measuring gzip bundle size against the 250 KB budget and the effort of the Gradle/Vite integration, then accept or reject the TypeScript UI here.

## Consequences

The `web` module is a placeholder until this is decided. Keeping the facade narrow (fold, policy, crypto, encode/decode) keeps either choice open.
