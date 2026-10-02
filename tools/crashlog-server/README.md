# Totum crash / diagnostics sink

Receives crash and diagnostics reports from the app and makes them readable. Runs as a
container on the Pi, reached through the existing nginx at
`https://crashlog.333133333.xyz`.

## Design rules, in priority order

1. **Never lose a report.** The raw body is written to disk *before* parsing or
   indexing. A malformed or truncated payload is still stored and still listed (flagged
   `unparsed`) — a report dropped for failing validation is worse than useless.
2. **Readable without tooling.** Reports are plain files under `/home/pi/crashlog-data`,
   so `cat` over SSH works even if this service is down.
3. **Searchable.** Key fields are mirrored into SQLite, so "every crash on commit
   abc123" or "group by exception" is one query.

## Endpoints

| Route | Purpose |
|---|---|
| `POST /ingest` | What the app posts to. **Unauthenticated** (see below). Returns `202` with an id |
| `GET /` | Browsable list, filterable by commit and exception |
| `GET /report/{id}` | One report: message, stack trace, and the **event trail** rendered as a timeline |
| `GET /report/{id}/raw` | The raw JSON as received |
| `GET /latest` | Newest report as plain text — the one-command triage |
| `GET /api/reports` | JSON list, for triaging without a browser |
| `GET /api/unread` | Reports nobody has judged, grouped by signature |
| `POST /api/report/{id}/triage` | One verdict for a whole report (`state`, `fixed_in`, `note`) |
| `POST /api/report/{id}/findings` | Add one **finding** to a report: `title`, `state`, `fixed_in`, `note` |
| `POST /api/finding/{fid}` | Re-judge a finding; fields left out are kept |
| `GET /api/report/{id}/findings` | A report's findings and whether it counts as **considered** |
| `GET /api/considered?since=YYYY-MM-DD` | Every report since a date, considered or not, with its findings |
| `GET /healthz` | Liveness |

## Why `/ingest` is unauthenticated

The app has to post without a human present, so it can't use the Google login that
gates everything else. A shared secret would have to ship inside the APK — and this
repo is public, so it would be extractable and therefore not a secret. The exposure is
bounded instead: nginx caps the body size and rate-limits the path, and the worst case
is junk reports, which are cheap to delete. **Everything except `/ingest` is behind
Google login.**

## Deploying / updating on the Pi

```bash
cd ~/code/totum && git pull
cd tools/crashlog-server && docker compose up -d --build
```

Its own compose project on purpose: bringing the sink up or down must never restart
nginx, Jellyfin or the VPN containers. nginx reaches it via `host.docker.internal:9140`,
the same way `graidentestdev` already does.

## Reading reports

Browse (Google login): <https://crashlog.333133333.xyz>

From a shell — no host port is published, so go via the files or the container:

```bash
# newest report, plain cat (works even if the container is down)
ssh pi@333133333.xyz 'cat "$(ls -t /home/pi/crashlog-data/reports/*/*.json | head -1)"'

# list recent
ssh pi@333133333.xyz 'ls -t /home/pi/crashlog-data/reports/*/*.json | head'

# the service's own pretty view / API
ssh pi@333133333.xyz 'docker exec totum-crashlog python -c "import urllib.request;print(urllib.request.urlopen(\"http://localhost:9140/latest\").read().decode())"'
```

## Pi deployment notes (paid for once, worth keeping)

- **nginx config lives in two places.** The tracked source is
  `dot-files/serverconfig/code/server_docker/data/nginx/app.conf`; `generate.sh` copies
  **tracked files only** into a root-owned `bin/`, and that copy is what nginx mounts.
  Editing only the source changes nothing live.
- **Bind-mounted *files* pin an inode.** nginx had been started against an older inode of
  `app.conf`, so appending to the host file was invisible inside the container (different
  inode *and* size). Fix without downtime: write through the container
  (`docker exec -i nginx_dewi sh -c 'cat > /etc/nginx/conf.d/default.conf'`) as well as
  the tracked source, then `nginx -s reload`.
- **`host.docker.internal` cannot reach a loopback-published port.** It resolves to the
  bridge gateway (172.17.0.1); a container published on `127.0.0.1:9140` is unreachable
  from there — it 504s. This service joins nginx's own `bin_private` network instead and
  is proxied by container name, so no host port is published at all.
- Reload, never restart: `docker exec nginx_dewi nginx -t && docker exec nginx_dewi nginx -s reload`
  leaves Jellyfin, the VPN containers and nginx's uptime untouched.

### The half-done fix that hid for a day (2026-07-25)

The note above says to write the container *and* the tracked source. The first time, only
the container got written — so both host copies still carried the broken
`host.docker.internal` form, and the next nginx recreate or `generate.sh` run would have
silently reverted the sink to 504 with nothing to explain why. Three habits from that:

- **Always verify the container's view after editing the host**, because the inode
  divergence means success on the host proves nothing:
  ```bash
  docker exec nginx_dewi grep -n 'set $crashlog' /etc/nginx/conf.d/default.conf
  ```
- **`sed -i` inside the container fails** — `Device or resource busy`, because it renames a
  temp file over the bind mount. Truncate in place instead:
  `sed '…' f > /tmp/x && cat /tmp/x > f`.
- **Don't anchor edits on `include … oauth2.partial`** — it appears in *five* server blocks,
  so a first-match insert lands in the wrong one. `server_name crashlog.…` is unique.

**`generate.sh` is Dewi's to run, not mine.** Its documented invocation
(`serverconfig/code/server_docker/README`) pulls `secret_url`, `duckdns_token`,
`cloudflare_api_token` and a MAC address out of **Bitwarden** via `bw list items`. Without an
unlocked vault it would regenerate the whole Pi's nginx config — every service, not just this
sink — with blanks where those secrets go. It is also **not** invoked by `run.sh` or at boot,
so `bin/` simply persists.

Which means patching both copies by hand is the correct procedure, and regeneration can be
*proved* equivalent without running it:

```bash
D=/home/pi/code/dot-files/serverconfig/code/server_docker
diff <(sudo sed -n '/server_name crashlog/,/^}/p' $D/data/nginx/app.conf | sed 's/{{TK_DOMAIN}}/333133333.xyz/g') \
     <(sudo sed -n '/server_name crashlog/,/^}/p' $D/bin/data/nginx/app.conf)
```

**Testing "does it survive a reboot" without rebooting.** A reboot restarts nginx, which
re-resolves its bind mounts and so picks up the *current* host file — the thing the inode
divergence hides. Mount it into a throwaway container to see exactly what a fresh start gets:

```bash
sudo docker run --rm -v $D/bin/data/nginx/app.conf:/x:ro alpine grep -n 'set $crashlog' /x
```

That is the check which proves the half-done fix above is really fixed: before it, a reboot
would have silently restored the 504.

## Considered: findings, not just reports

One report usually holds several separate things: the bug in Dewi's note, an old crash still in
the logcat buffer, a noisy sync line. Each one is a **finding**, judged on its own with the same
states as a report (`new`, `triaged`, `fixed`, `wontfix`, `noise`). A report is **considered**
once every finding has a verdict other than `new`. A report judged as a whole before findings
existed (2026-10-02) still counts.

The report's own `state` follows its findings, so the list, its filters and `/api/unread` stay
true:
- any unjudged finding leaves the report `new`;
- one shared verdict becomes the report's verdict, with `fixed_in` when they agree;
- a mix reads `triaged`.

The report's `note` becomes a one-line summary of its findings.

From the laptop, `tools/crashlog-server/triage.sh` does all of it over ssh:

```bash
tools/crashlog-server/triage.sh considered 2026-10-01   # ✅/⬜ per report, with its findings
tools/crashlog-server/triage.sh add <report> fixed "no summons after a 2nd Restart" v0.1.556
tools/crashlog-server/triage.sh set <finding-id> noise
```

Tests: `tests/test_findings.py` (unittest). There is no Python toolchain for the server on the
laptop, so run them in the server's own image on the Pi:
`docker run --rm -e PYTHONDONTWRITEBYTECODE=1 -v <dir>:/src -w /src totum-crashlog:local python -m unittest discover -s tests`.

## Size

Measured 2026-10-02: 572 reports in 59 MB on the Pi, against the `CRASHLOG_MAX_TOTAL_MB` cap of
512 MB, which prunes the oldest (and their findings). On the phone, a report waits in
`files/diagnostics` only until it is accepted: at most 50, about 150 KB each. Every report says
how many are waiting and their size (`pendingReports`, `pendingReportsKb`).
