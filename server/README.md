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
