# Installing a host

You need a machine that is switched on all the time, with a name on the internet (for example `lendger.example.org`) pointing at it. See [requirements.md](requirements.md) for how big it must be; a small virtual server or a Raspberry Pi 4 is enough for a circle of a few hundred.

There are two ways to run it. Docker is simpler; the plain jar needs only Java.

## Option 1: Docker (recommended)

You need Docker with the Compose plugin, and a built jar. On any computer with this repository and Java 17:

```bash
./gradlew :server:shadowJar        # makes server/build/libs/lendger-server.jar
```

Copy the repository (or just `deploy/` and `server/build/libs/lendger-server.jar`) to the host, then on the host:

```bash
# 1. A folder for backups. Use a different disk or a mounted network drive if you can: backups on the same disk
#    as the data are lost together with it. The server runs as user 10001 and must be allowed to write there.
sudo mkdir -p /srv/lendger-backups && sudo chown 10001:10001 /srv/lendger-backups
export BACKUP_DIR_ON_HOST=/srv/lendger-backups

# 2. Build the image and start the server.
docker compose -f deploy/compose.yaml up -d --build

# 3. Check it.
docker compose -f deploy/compose.yaml ps            # the circles service should say "healthy" after about 30 seconds
curl http://127.0.0.1:8080/healthz                  # prints: ok
```

To keep `BACKUP_DIR_ON_HOST` and the other settings between logins, put them in a file called `.env` next to `deploy/compose.yaml` (one `NAME=value` per line). The server now takes a full backup every 24 hours into that folder and keeps the newest 7.

### HTTPS

Members' devices must reach the server over HTTPS. If you have no proxy of your own, the compose file includes one that gets and renews certificates by itself:

1. Make sure the name points at the host, and that ports 80 and 443 are open.
2. Add to `.env`: `LENDGER_DOMAIN=lendger.example.org` and `LENDGER_TRUST_PROXY=true`.
3. Start with the proxy: `docker compose -f deploy/compose.yaml --profile tls up -d`.

If you use your own proxy (nginx, Traefik, a cloud load balancer), forward to port 8080, set `LENDGER_TRUST_PROXY=true`, and **switch response buffering off and allow long connections** for `/api/v1/circles/*/events/stream`, which is a live stream. For nginx that is `proxy_buffering off;` and `proxy_read_timeout 1h;`.

## Option 2: the plain jar

You need Java 17 or newer (`java -version`). Create a user and folders, copy the jar, and write the settings:

```bash
sudo useradd --system --home /var/lib/lendger --shell /usr/sbin/nologin lendger
sudo mkdir -p /opt/lendger /var/lib/lendger /srv/lendger-backups /etc/lendger
sudo cp lendger-server.jar /opt/lendger/
sudo chown lendger:lendger /var/lib/lendger /srv/lendger-backups
sudo tee /etc/lendger/lendger.env >/dev/null <<'ENV'
LENDGER_DATA_DIR=/var/lib/lendger
LENDGER_BACKUP_DIR=/srv/lendger-backups
LENDGER_BACKUP_INTERVAL_HOURS=24
LENDGER_BIND=127.0.0.1
LENDGER_PORT=8080
LENDGER_TRUST_PROXY=true
ENV
sudo cp deploy/lendger-server.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now lendger-server
curl http://127.0.0.1:8080/healthz
```

`LENDGER_BIND=127.0.0.1` keeps the server reachable only from this machine, which is what you want behind a proxy on the same host (install Caddy or nginx and point it at `127.0.0.1:8080`, with the stream notes above). Logs: `journalctl -u lendger-server`.

## Settings

All are environment variables and all are optional except where noted.

| Setting | Default | What it does |
|---|---|---|
| `LENDGER_DATA_DIR` | `./data` | Where the database (`lendger.db`) and `blobs/` live |
| `LENDGER_BIND`, `LENDGER_PORT` | `0.0.0.0`, `8080` | Where to listen |
| `LENDGER_ALLOW_CREATE` | `true` | Whether anyone who can reach the server may start a new circle. See below |
| `LENDGER_TRUST_PROXY` | `false` | Set `true` behind a proxy so each member is rate limited separately instead of all sharing the proxy's address |
| `LENDGER_RATE_LIMIT` | `true` | Keep on |
| `LENDGER_BLOB_QUOTA_MIB` | `256` | Photo storage allowed per circle |
| `LENDGER_BACKUP_DIR` | none | **Required** for scheduled backups and for upgrading an existing database |
| `LENDGER_BACKUP_INTERVAL_HOURS` | off | Take a full backup this often (needs `LENDGER_BACKUP_DIR`) |
| `LENDGER_BACKUP_KEEP` | `7` | Scheduled backups to keep |
| `LENDGER_ADMIN_IMPORT_MAX_MIB` | `64` | Biggest circle a steward may import over the internet; bigger ones use the command line |
| `LENDGER_LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error` |

## Your first circle, and who may create one

By default anyone who can reach the server can create a circle on it. That is fine for the first hour, while you and your members set one up. Afterwards, if the host is for one community, close it: set `LENDGER_ALLOW_CREATE=false` and restart. Existing circles are not affected. You can still bring a circle in yourself with the commands in [handover.md](handover.md).

## Knowing it is healthy

- `GET /healthz` answers `ok` when the process is up. `GET /readyz` answers `ready` when the database works. `GET /version` shows the version.
- Logs are one JSON line per event on standard output. They never contain message content, names, keys, tokens, circle ids or addresses.
- Look at the backup folder once a week: new files should be appearing.
- Keep the operating system updated and the firewall closed except for ports 80 and 443 (and 22 for you).

## What you can and cannot do

You can list the circles on the host (`list`), back up, restore, export and import them. You cannot read a circle, reset a member's access or recover a lost key: those are by design the members' and stewards' to do. If a member loses all their devices, a steward helps them back in from within the app.

## Command-line tools

With Docker, put `docker compose -f deploy/compose.yaml run --rm lendger` in front of a command; with the jar, `java -jar /opt/lendger/lendger-server.jar` (as the `circles` user, with the settings above in the environment).

| Command | What it does |
|---|---|
| `serve` | Start the server |
| `migrate` | Create or upgrade the database (takes a backup first if one exists) |
| `list` | Show the circles on this host: id, date created, events, devices, stewards |
| `backup <folder>` | Write a full backup into the folder |
| `restore <file> [--force]` | Put a backup back |
| `verify <file>` | Check a backup or an export without touching anything |
| `export <circleId> <file>` | Write one circle to a file |
| `import <file>` | Bring a circle in from a file |
| `import-token <circleId> [--hours N]` | Let a steward import that one circle over the internet (see [handover.md](handover.md)) |
