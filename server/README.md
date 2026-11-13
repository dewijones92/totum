# server/: what Totum runs on the Pi

The app's back end on the home Pi, one compose project per service, deployed from the Pi's clone of this
repo (`~/code/totum`). The crash and diagnostics sink has its own home in `tools/crashlog-server/` and is
deployed the same way.

| Service | What it does for the app | Network |
|---|---|---|
| `prowlarr/` | Torrent search (The Pirate Bay, YTS, 1337x). Search only: no download client, no applications. | `bin_private` |
| `flaresolverr/` | Solves 1337x's Cloudflare challenge for Prowlarr, about 90 s a time. | `bin_private` |
| `torrserver/` | Streams a torrent's files with real seeking, plus its `/torrents` watchdog timer. | gluetun's netns |
| `totum-audio/` | Audio-only HLS of a TorrServer stream (`/audio/<hash>/<index>/index.m3u8`), about 2.1 MB a minute against 15.2 for the video. | gluetun's netns |
| `totum-auth/` | A token per device: nginx asks it before every request to `totum.`; `totumauth.` (Google-gated) mints them ([ADR 90](../docs/adr/0090-a-token-per-device-and-none-in-links.md)). | `bin_private` |
| `ai-service/` | `totum ai-serve`: Claude Code with Haiku only, `POST /complete`, `GET /health`, `GET /usage` for any service on `bin_private` (no port published) ([ADR 113](../docs/adr/0113-an-ai-service-on-the-pi-and-health-the-pi-judges.md)). Built from a release like totum-fetch, from the shared `totum-cli/Dockerfile` (`target: ai`): `TOTUM_VERSION=0.1.N docker compose up -d --build`. Claude's sign-in is `CLAUDE_CODE_OAUTH_TOKEN` in `/var/lib/ai-service/claude.env` (600); usage is logged to `/var/lib/ai-service/home/.totum/ai-usage.jsonl`. The image carries `jq` (✨ Ask's `ask-state` job runs it on a snapshot file the service writes to a private temp directory for that one call and deletes after; the per-caller tool table in `CallerTools.kt` is the one gate) ([ADR 140](../docs/adr/0140-ask-answers-about-your-own-app-with-jq-only.md)). | `bin_private` |
| `totum-fetch/` | `totum serve` (the CLI): ✨ Ask's jobs, thinking through the AI service (`TOTUM_AI_URL`), at `totum./fetch/` behind the device token ([ADR 109](../docs/adr/0109-ask-haiku-picks-what-the-app-finds-and-ranks-which-copy.md)). Built from a release: `TOTUM_VERSION=0.1.N docker compose up -d --build`. `/var/lib/totum-fetch/fetch.env` holds `TOTUM_REWRITE=https://totum.<home>/ts/=http://gluetun:8090/`, so a torrent job reads TorrServer directly. ✨ Ask's app-data snapshots arrive as `PUT /fetch/ask-state` (gzipped, about 0.3 to 3 MB) and are held in memory for 10 minutes, which the totum server block already allows (`client_max_body_size 0`, checked 2026-11-07) ([ADR 140](../docs/adr/0140-ask-answers-about-your-own-app-with-jq-only.md)). nginx's `/fetch/` location (in the hand-managed totum-guard snippet) has `proxy_buffering off`: a 2 GB file was otherwise spooled to the Pi's disk first ("an upstream is buffered", 2026-10-31). Downloads at home land in `/home/pi/ext_files/media/totum`; the container runs as uid 1000 (pi) and mounts the whole `/home/pi/ext_files/media` as `/media`, so a finished torrent is renamed into Jellyfin's `videos/movies` or `videos/series` (one filesystem, no copy) and Jellyfin is told through `TOTUM_JELLYFIN_URL`/`TOTUM_JELLYFIN_KEY` in `fetch.env` (an API key named "Totum", Dashboard → API Keys) ([ADR 111](../docs/adr/0111-finished-torrents-at-home-are-filed-into-jellyfin.md)); a folder owned by another uid (911) cannot be written, so the file goes into a fresh clean-named folder beside it (and if none can be made it stays in `totum/`, with the reason on the health banner, [ADR 166](../docs/adr/0166-home-torrents-hold-together-retries-shown-main-file-first-and-a-fixed-remux.md)); an MKV whose Tracks come after its first cluster is remuxed with `ffmpeg -c copy` when it is filed; the Pi's YouTube sign-in, handed over by the phone, is `/var/lib/totum-fetch/home/.totum/youtube.json` (600). It also plays each target's queue (`/targets`, [ADR 152](../docs/adr/0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md); Bluetooth speakers with mpv, [ADR 116](../docs/adr/0116-speaker-queues-play-on-the-pi.md), and Chromecast TVs through `pychromecast`, [ADR 153](../docs/adr/0153-the-pi-runs-the-phones-sound-processing.md)): it mounts pi's PulseAudio socket (`/run/user/1000/pulse/native`, so pi's PulseAudio must be running before the container starts, or Docker makes a directory there) and cookie, and the system D-Bus for `bluetoothctl`; queues are saved in `home/.totum/targets/` (`target-<id>.json`; the older `speakers/` files are not read). **TVs and Chromecasts** are named in `fetch.env`: `TOTUM_CAST_TARGETS=LG TV=192.168.0.30@20:28:bc:6b:f7:f8;Chromecast Ultra=192.168.0.31` (name=address, no mDNS discovery: multicast does not cross `bin_private`; an optional `@mac` makes the TV wake-on-LAN-able, so it is listed as 💤 when off and a play wakes it, with `TOTUM_WAKE_BROADCAST=<comma list>` for extra broadcast addresses besides 255.255.255.255 and the TV's own /24 — a broadcast from `bin_private` is untested, [ADR 152](../docs/adr/0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md)), and `TOTUM_CAST_BASE=http://<the Pi's LAN address>:8771` is the address the TV uses to fetch what the Pi serves: the phone's own cast stream, `/c/<token>/manifest.mpd` (or `live.mpd`) with its video relayed in ranges, processed AAC segments, captions and live segments ([ADR 156](../docs/adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)), and, for a stream the core declines and for ffmpeg and mpv over loopback, a YouTube stream relayed in ranges at `/y/<token>.<ext>` (YouTube stops a plain fetch after about a megabyte); the compose file publishes `8771` on the LAN and nothing else answers there, and only per-offer random paths do. The image carries `pychromecast` and `chromecast_helper.py` (`TOTUM_CAST_HELPER`). A processed play needs `ffmpeg` (in the image). `TOTUM_SPEAKERS_KEEP_AWAKE=Soundmic` (names or ids, comma-separated, each with an optional `@07:00-22:30` window) keeps those speakers linked after a queue ends, reconnects them if they drop and sends two seconds of inaudible sound to their sink about once a minute while nothing plays (`TOTUM_SPEAKERS_KEEP_AWAKE_SIGNAL=silence` or `noise`; speakers not named are untouched); a speaker's volume slider writes its own Bluetooth transport volume with `busctl` (the image now carries the `systemd` package for it; mpv stays at 100%) and falls back to mpv's volume, logged, when the speaker offers none ([ADR 165](../docs/adr/0165-a-speaker-is-kept-awake-and-has-one-volume-its-own.md)). `TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND=Soundmic` (names or ids, comma-separated) names the speakers that are switched on by hand, so a failed link says "Switch it on, then try again" instead of "off or out of range"; what Totum launched on a TV is remembered across a restart in `home/.totum/targets/handback-<id>.json` ([ADR 158](../docs/adr/0158-targets-end-of-batch-fixes.md)). `8770` is not published; `8772` is a second listener that serves only `/targets*` and `/health` (it refuses `/jobs`, `/files`, `/account`, `/ask`, `/jellyfin` and the rest) and is published on the Pi's loopback only (`127.0.0.1:8772`, no token: the same trust as `hermes-audio-ctl` on 8099; `TOTUM_LOOPBACK_PORT` moves it; [ADR 158](../docs/adr/0158-targets-end-of-batch-fixes.md)), and `/var/lib/hermes-audio` is mounted read-only at `/sounds` so an item `file:///sounds/<name>` plays a file the Pi holds (`TOTUM_SOUNDS` to move it; [ADR 155](../docs/adr/0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md)). Home Assistant's brown noise and jazz call `hermes-audio-ctl`, which becomes an adapter over `/targets` (`tools/handoff/hermes-audio/`, deployed by hand; [ADR 152](../docs/adr/0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md)). | `bin_private` |

State lives outside the repo, in `/var/lib/prowlarr`, `/var/lib/torrserver`, `/var/lib/torrserver-watchdog` and
`/var/lib/totum-auth`, and is covered by the Pi's `pi-state-backup`.

## Who gets in (2026-10-24)

- `totum.` asks totum-auth on every request: `/var/lib/totum-auth/snippets/totum-guard.partial` is an `auth_request`
  to its `/check`. A device's token is only accepted as the `X-Totum-Token` header; a token in a link is refused.
- `totumauth.` (behind the Google gate) proxies to its `/signin` with a secret header from
  `/var/lib/totum-auth/snippets/totum-token.partial`, so nothing else on `bin_private` can mint tokens.
- Devices: `sudo docker exec totum-auth python server.py devices`, `... revoke <id>`. The old shared token
  (`/var/lib/totum-auth/token`) is accepted until `... retire-legacy`.
- Both partials are single-file mounts: rewrite them in place (`cat new > file`, keeping the inode), then
  `docker exec nginx_dewi nginx -t && docker exec nginx_dewi nginx -s reload`.

## Search speed (2026-10-24)

- **1337x is the custom definition `prowlarr/definitions/1337x-fast.yml`**: Prowlarr's own 1337x with only pages
  1 and 2, sorted by seeders (40 best-seeded results, ~5 s warm against ~12 s for four pages; the definition's
  3 s `requestDelay` between pages is kept). Deploy: copy it to
  `/var/lib/prowlarr/config/Definitions/Custom/`, `docker restart prowlarr`. The stock four-page indexer is kept
  disabled as "1337x (4 pages)".
- **AudioBook Bay is the custom definition `prowlarr/definitions/audiobookbay.yml`** (2026-10-31), added as indexer 11
  ("AudioBook Bay"). Prowlarr no longer ships one; the site answers 404 to Prowlarr's own User-Agent, so the
  definition sends a browser one for the search and for the book page the info hash is read from when a result is
  chosen. Two pages (~14 books, ~3.5 s). Deploy like 1337x-fast. The phone asks it, with The Pirate Bay's Audio
  Books, for the audiobook category only ([ADR 105](../docs/adr/0105-audiobooks-are-books-from-the-archive-and-torrents.md)).
- **`prowlarr/search-warmer.{sh,service,timer}`** searches every FlareSolverr-backed indexer every 15 minutes, so
  Cloudflare's clearance is held and a real search skips the ~80 s solve. Its log (`journalctl -t search-warmer`)
  says each time whether the clearance had lapsed. Link it like the watchdog.
- **The phone also asks Torrentio directly** (Cinemeta for the IMDb id, then Torrentio's streams), beside Prowlarr;
  nothing runs on the Pi for it ([ADR 86](../docs/adr/0086-torrentio-beside-prowlarr.md)).
- **1337x results carry no magnet** in Prowlarr's search response, only `downloadUrl` (Prowlarr fetches the
  details page when it is asked) and the page address; the app asks that link for the magnet (a 301 to it) when a
  result is chosen ([ADR 89](../docs/adr/0089-a-result-without-a-magnet-is-resolved-when-chosen.md)).
- **Search stays on the home IP** (Dewi, 2026-10-24): Prowlarr and FlareSolverr sit on `bin_private`, only the
  swarm (TorrServer, totum-audio) uses PureVPN. Measured: the first two exit as the home address, the second two as
  PureVPN's.

## TorrServer's own settings (2026-10-31)

Some of TorrServer's behaviour lives in its database (`/var/lib/torrserver`), not in this repo, so it
is written down here. Set through its API from inside the container, e.g.
`docker exec torrserver wget -q -O - --post-data='{"action":"get"}' http://127.0.0.1:8090/settings`,
then post `{"action":"set","sets":{…the whole object…}}` with the change.

| Setting | Value | Why |
|---|---|---|
| `UploadRateLimit` | `1` (KB/s) | Dewi, 2026-10-31: "set upload speed to like 1 KB a second". Was `0` (unlimited). Read back after setting. |
| `DisableUpload` | `false` | Left on: a peer that uploads nothing is choked by others and downloads slower. |

## What stays in the Pi's `dot-files` repo

- **nginx**: one generated config for the whole Pi. The app reaches all of this through the `totum.` vhost
  (gated by totum-auth, 180 s timeout for the Cloudflare step); people reach Prowlarr and TorrServer at
  `prowlarr.`/`torrserver.` behind the Google gate. nginx proxies by container name, so it does not care where
  a compose file lives.
- **gluetun** (`vpn-stack/`), whose network namespace `torrserver` and `totum-audio` share, so every peer
  connection leaves through PureVPN. Their ports, 8090 and 8099, are in its `FIREWALL_INPUT_PORTS`.
- **`vpn-stack/reattach-passengers.sh`**, which the watchdog runs before every probe.

## Deploy

```bash
ssh pi@333133333.xyz
cd ~/code/totum && git pull --ff-only -q origin main
cd server/<service> && sudo docker compose up -d                     # prowlarr, flaresolverr, totum-auth
cd server/<service> && sudo docker compose up -d --force-recreate    # torrserver, totum-audio
cd server/totum-audio && sudo docker compose up -d --build --force-recreate   # after changing server.py
```

Each compose file pins its project `name:`, so the project (and `totum-audio`'s image) keeps its name whichever
directory it runs from. `updatepi.sh` finds these projects through docker's own labels and updates them weekly.

## Rules that bite

- **Recreate, never just start, anything in gluetun's netns.** `network_mode: container:gluetun` pins the
  container to gluetun's container ID; when gluetun is recreated (an update, a reboot) its passengers stay "Up"
  and serve nothing, and nginx answers 502. `reattach-passengers.sh` fixes that automatically, but only for a
  container whose compose labels point at a directory that exists, so after moving a project always recreate
  it from its new directory.
- **Probe `/torrents`, never `/echo`.** TorrServer once answered `/echo` in 5 ms for two days while `/torrents`
  (list and add) was deadlocked. The compose healthcheck and the watchdog both POST `{"action":"list"}`.
- **Prowlarr's `magnetUrl` carries its API key**; a result's real magnet is `guid`.

## Checking it from the Pi

```bash
# search
K=$(sudo grep -oP '(?<=<ApiKey>)[^<]+' /var/lib/prowlarr/config/config.xml)
sudo docker exec -e K="$K" nginx_dewi sh -c 'curl -s -H "X-Api-Key: $K" "http://prowlarr:9696/api/v1/search?query=big+buck+bunny&type=search"' | head -c 300
# FlareSolverr
sudo docker exec nginx_dewi curl -s http://flaresolverr:8191/
# TorrServer, the endpoint it exists for
sudo docker exec nginx_dewi curl -s -X POST -d '{"action":"list"}' http://gluetun:8090/torrents | head -c 300
# the watchdog
systemctl list-timers torrserver-watchdog.timer; journalctl -t torrserver-watchdog -n 5
```

The watchdog's and the warmer's units are symlinked from here into `/etc/systemd/system`
(`sudo systemctl link /home/pi/code/totum/server/torrserver/torrserver-watchdog.{service,timer}
/home/pi/code/totum/server/prowlarr/search-warmer.{service,timer}`, then
`sudo systemctl enable --now torrserver-watchdog.timer search-warmer.timer`).

Moved here from `dot-files` on 2026-10-23 ([ADR 85](../docs/adr/0085-the-pi-back-end-lives-in-this-repo.md)).

## The cast relay route (hand-managed, 2026-10-31)

`/var/lib/totum-auth/snippets/totum-guard.partial` (root, not in git, mounted into nginx) carries, after the guard,
a `location ~ "^/cast/(c/[0-9a-f]{16}/.*)$"` with `auth_request off` that proxies to `http://192.168.0.19:18766/$1`,
the `wg-home` relay to a phone on the VPN (dot-files ADR 7; Totum ADR 68). A TV cannot send the token, so only
per-session cast paths pass. A backup of the snippet before the change is beside it (`.bak-2026-10-31`). Edit it in
place (nginx pins the file's inode), `docker exec nginx_dewi nginx -t`, then `nginx -s reload`.

