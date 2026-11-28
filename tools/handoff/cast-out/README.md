# Casting away from home: what to change on the Pi (ADR 182)

Nothing here has been applied. The builder was not allowed to change the Pi; the orchestrator (or Dewi) applies it. Everything below was
checked against the Pi's real files read over ssh, and the nginx part was run in a real local nginx (see the ADR's Evidence).

Files in this folder:

- `nginx-http-level.patch`: a unified diff for `serverconfig/code/server_docker/data/nginx/nginx.conf` in the `dot-files` repo (applies cleanly
  to the Pi's copy of 2026-11-27; `patch -p1 --dry-run` first). It adds a rate-limit zone, a map that cuts a link to six characters, and a
  log format that uses it.
- `totum-guard.cast-out.partial`: two `location` blocks to append to `/var/lib/totum-auth/snippets/totum-guard.partial` (root, not in git,
  mounted into nginx), after the "cast relay" location.

## Steps

1. **Release a CLI that has this change** (the `totum-fetch` image downloads the CLI from the GitHub release named by `TOTUM_VERSION`).
2. **nginx.conf, in the dot-files repo (template) and on the box.** The vault is usually locked, so the house deviation applies
   (dot-files `CLAUDE.md`, "nginx pins the inode"): edit the template and `bin/` identically, in place.
   ```bash
   cd ~/code/dot-files
   patch -p1 --dry-run < ~/code/totum/tools/handoff/cast-out/nginx-http-level.patch   # must say it applies
   patch -p1 < ~/code/totum/tools/handoff/cast-out/nginx-http-level.patch
   ```
   For the live copy (`bin/data/nginx/nginx.conf`, root, mode 700, bind-mounted into `nginx_dewi`): back it up to `/home/pi/castout-nginx-backup-<stamp>/`, then
   write the patched text through the same inode (`cat patched > file`, never `sed -i` or a rename), and check **from inside the container**:
   `docker exec nginx_dewi grep -n castout /etc/nginx/nginx.conf`. This file is read at start, so it needs `nginx -s reload` after the snippet below.
3. **The location snippet.** Back up `/var/lib/totum-auth/snippets/totum-guard.partial` beside itself (`.bak-<date>-castout`), append the content of
   `totum-guard.cast-out.partial` **in place** (`sudo tee -a` keeps the inode), then:
   ```bash
   docker exec nginx_dewi nginx -t
   docker exec nginx_dewi nginx -s reload
   docker exec nginx_dewi grep -n 'cast-out' /etc/nginx/snippets/totum-guard.partial
   ```
   The log file `/var/log/nginx/castout.log` is created by nginx on the first request.
4. **fetch.env.** Add to `/var/lib/totum-fetch/fetch.env`:
   `TOTUM_CAST_PUBLIC_BASE=https://totum.<domain>/cast-out`, that is `https://`, the host the phone is signed in to (the `totum.<domain>`
   vhost) and `/cast-out`, no trailing slash. Nothing to add to the compose file: 8773 is not published; nginx reaches it as
   `totum-fetch:8773` on `bin_private`. Then `TOTUM_VERSION=<the release> docker compose up -d --build` in `server/totum-fetch`
   (this also gives the container its new code). Read `docker logs totum-fetch | grep cast-out`: it must say
   `casting away from home is ON: links are https://totum.<domain>/cast-out/<link>/manifest.mpd` and `the public cast gate listens on 8773`.
5. **Probe it from outside, with no token** (from a machine off the home network, or the laptop through the public name):
   - `curl -s -o /dev/null -w '%{http_code}\n' https://totum.<domain>/cast-out/session` → **404** (the mint route is not public)
   - `curl -s -o /dev/null -w '%{http_code}\n' https://totum.<domain>/cast-out/$(printf 'a%.0s' $(seq 64))/manifest.mpd` → **404**
   - `curl -s -o /dev/null -w '%{http_code}\n' https://totum.<domain>/` → still the gate's 401
   - On the Pi: `curl -s http://192.168.0.19:8771/lan-check` → `totum-home`
6. **Check the log hygiene after the first real cast:** `grep -c '<the whole link>' /var/log/nginx/access.log /var/log/nginx/castout.log` is 0 for both
   (the phone's log shows the first six characters to look for).
7. Record the deviation in dot-files' `CLAUDE.md` (the "OUTSTANDING" list: template and `bin/` edited by hand, backups in `/home/pi/castout-nginx-backup-*`), and
   run a real `generate.sh` when the vault is next open.

## Rolling back

Remove the two blocks from the snippet and the three lines from `nginx.conf` (or restore the backups), `nginx -s reload`, and unset
`TOTUM_CAST_PUBLIC_BASE` (the gate then does not start and the phone serves every cast itself, as before). Nothing else depends on it.

## How Dewi tests it at a friend's

The home Wi-Fi plus mobile data is **not** enough: a Chromecast has to be on the network the phone is on, and a phone on mobile data cannot see one.
The simplest stand-in is a second phone's hotspot:

1. A second phone makes a hotspot (mobile data on). Put a Chromecast on it (set it up from the Google Home app while on the hotspot) and join Dewi's
   phone to it. That network cannot reach `192.168.0.x`, which is exactly "away".
2. Play a Jellyfin film or a 🏠 home copy in Totum, tap Cast, pick the Chromecast.
3. Look in the phone's diagnostics (or `adb logcat -s dewidebug | grep cast-out`) for the `home network:` line saying it is NOT on the home network,
   then `the friend's Chromecast fetches it from the home server (session xxxxxx…)`. The film should start within a few seconds; seek with Totum's bar.
4. On the Pi: `docker logs totum-fetch | grep cast-out` shows the session, what it was asked for, and on stop
   `ended: the phone ended the cast; N request(s), … KB served`.
5. Join the phone to the home Wi-Fi and cast the same film: it should log `the phone is on the home network, where it serves home items as before`.

What to expect and what is not built: skip silence is off on this route, a live stream and a YouTube stream stay phone-direct, and if anything in the
chain fails the phone serves the item itself and says why in the `cast-out` line.
