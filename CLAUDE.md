# Circles (community sharing app)

Read `docs/spec/community-sharing-app-design.md` (why) and `docs/spec/community-sharing-app-spec.md` (what to build) before changing anything. Decisions are in `docs/adr/`. The parent `ClaudeCodeExperiments/CLAUDE.md` conventions apply (Kotlin, immutable data, sealed results instead of exceptions for expected errors, named constants, JUnit5 and MockK, clean architecture, README).

## Where things are
- `shared/` Kotlin Multiplatform (JVM + JS). Policy is in `shared/src/commonMain/kotlin/circles/policy/CircleAccess.kt`; tests in `shared/src/commonTest` (kotlin.test, run on both targets).
- `server/` Ktor, stub only until M1. `web/`, `deploy/`, `tools/` are placeholders.
- Milestones are in spec section 9. M0 (skeleton) is done; M1 (event log and sync) is next.

## Rules for this project
- No custom cryptography: libsodium primitives only (spec section 5). No telemetry or third-party network calls.
- Policy code is common Kotlin: no `java.*`, no `String.format`, no `kotlin.math.round` (ties differ between JVM and JS). Use integer days (ADR 0007).
- A field goes in an event's `public` part only if the server must read it; everything else is `encrypted` (ADR 0004).
- Every behaviour in the spec needs a test. Write an ADR for decisions that are hard to reverse; never edit an accepted ADR, supersede it.
- Run `./gradlew check` (both targets plus server) before saying something is done.

## Session protocol
At the end of each session, update this file with architectural or tooling decisions made, milestones completed or in progress, and gotchas learned.

## State (2026-11-02)
- Done: M0 skeleton, policy corrections C1-C13 with tests, ADRs 0001-0009 (0009 web UI stack is Proposed).
- Open decisions are listed at the bottom of `docs/adr/README.md`.
- Gotchas: `@JvmInline` needs `import kotlin.jvm.JvmInline` in common code (JS fails without it; found by the JS compile, not the JVM one); a JUnit test method that returns a value silently does not run (use `= testApplication { ... }` or `runTest`, not a value-returning expression); the Gradle wrapper and versions were copied from the HomeFinder project, update both together if they drift.
