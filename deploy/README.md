# deploy

Files for running the server. The guide is [docs/operator/install.md](../docs/operator/install.md).

| File | What it is |
|---|---|
| `Dockerfile` | A small non-root image (Alpine, Java 17) around the built jar. Build the jar first: `./gradlew :server:shadowJar` |
| `compose.yaml` | The server with a data volume and a required backup folder; an optional Caddy proxy (`--profile tls`) that gets HTTPS certificates by itself |
| `Caddyfile` | The proxy's settings: it forwards to the server and does not buffer the live-update stream |
| `lendger-server.service` | A hardened systemd unit for running the jar directly |

What was checked: the image was built and run (it starts as user 10001, reports healthy, answers `/healthz` and `/version`, and takes a backup into the mounted folder while running); `compose.yaml` is accepted by `docker compose config` with and without the `tls` profile; the Caddyfile passes `caddy validate`; the systemd unit passes `systemd-analyze verify`. **Not run:** a public HTTPS certificate being issued, a full `docker compose up` with a real domain, or the systemd unit on a live machine. Raspberry Pi: the image is built for the machine you build it on (use `docker buildx` for another architecture).
