# 0017. One runnable jar with the Shadow plugin

- Status: Accepted
- Date: 2026-11-02

## Context

The design (section 8) promises "one runnable jar" so an operator can copy one file to a Raspberry Pi or VPS. The Gradle `installDist` output is a folder of 50 jars.

## Decision

Use the `com.gradleup.shadow` plugin. `./gradlew :server:shadowJar` writes `server/build/libs/circles-server.jar` (run with `java -jar circles-server.jar serve`). It merges `META-INF/services` files (needed for the SQLite JDBC driver and Netty), drops BouncyCastle's signature files, which are invalid after repackaging, and drops Netty's HTTP/3 (QUIC) natives, which the server does not use (about 11 MB). SQLite's native libraries for every platform stay in, because the target includes ARM boards.

## Consequences

The jar is about 39 MB, mostly SQLite natives, BouncyCastle (used for one hash) and Kotlin libraries. Docker and systemd recipes in M5 build on this jar. If the size matters, shrinking BouncyCastle or the SQLite natives is a later, separate change.
