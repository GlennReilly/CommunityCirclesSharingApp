# tools

Developer tools that are not part of the Gradle build.

- `vectors/` Python scripts that produce independent test vectors, so the Kotlin code is checked against something other than itself: `shamir_vectors.py`, `cipher_vectors.py` (PyNaCl), `argon2_vectors.py` (libsodium), `device_seed_vectors.py`, `key_commitment_vectors.py`, `recovery_code_vectors.py`, and `qr_vectors.py` (writes `shared/src/commonTest/kotlin/circles/qr/QrVectors.kt`). Each file says at the top which ADR it serves and how to run it (`python3 tools/vectors/<name>.py`, needing PyNaCl, `cryptography` or `qrcode` as noted).
- `browser-e2e/` end-to-end checks in real Chrome against the built jar: `e2e.mjs` (full flow, axe-core with `A11Y=1`), `sw-e2e.mjs` (service worker), `load-e2e.mjs` (first-visit time under throttling), shared helpers in `lib.mjs`. See [browser-e2e/README.md](browser-e2e/README.md).
- `bundle-analysis/` `gz-by-source.mjs` prints the production bundle's gzipped size by origin, with notes and dated measurements. See [bundle-analysis/README.md](bundle-analysis/README.md).

The 300-member load test is not here: it is a Gradle task, `./gradlew :server:loadTest` (see `docs/operator/requirements.md`).
