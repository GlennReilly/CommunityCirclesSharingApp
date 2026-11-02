# 0002. Kotlin Multiplatform shared module, Ktor server, SQLite

- Status: Accepted
- Date: 2026-11-02

## Context

The same rules (event validation, policy, sync logic) must run on the server and in the browser, or the server and clients will disagree about what is valid. The project must be deployable as one small unit with no external services.

## Decision

- A `shared` Kotlin Multiplatform module (JVM and JS IR) holds the model, encoding, validation, policy and sync logic.
- The `server` module is a Ktor (Netty) JVM app using SQLite (WAL mode) with hand-written SQL. No ORM, no external database.
- Gradle Kotlin DSL with a version catalog; Kotlin 2.x, JVM 17+.
- Domain code in `shared` has no framework dependencies.

## Consequences

One implementation of the rules. Kotlin/JS size and maturity are a risk, tracked in ADR 0009. No cryptography here: libsodium bindings are a separate decision (see the README's open decisions).
