---
title: Torrents (public-domain film & TV)
kind: feature
status: shipped
area: torrent
updated: 2026-10-30
---

# Torrents (public-domain film & TV)

Search for a film, tap it, watch it. The Pi torrents; the phone is a remote control and a screen.

Dewi asked for classic public-domain films and TV — *The Beverly Hillbillies*, *Dragnet* — findable
and watchable "just like YouTube" (2026-08-01), with **no configuration to type** (2026-08-01:
*"make sure that the torrent ux works out the box … so i dont have to insert any config etc etc"*).

## Why this doc exists

Because the backlog said the opposite. Two todos claimed "app side not started" and "sign-in may
already be shipped, not checked" — and on 2026-08-06 that stale pair sent this session's opening
recommendation to Dewi in the wrong direction: I proposed building what already existed. The code was
read afterwards and the whole path was there. **A status nobody re-checks is worse than no status.**

## What is shipped

| Piece | Where |
|---|---|
| Search across indexers | `TorrentSearchSource` → `HttpHomeTorrentServer.search` (Prowlarr), best-seeded first |
| Old AVIs | MP3 stored across frames is cut into whole frames before the decoder (`totumExtractors()`, [ADR 87](../adr/0087-mp3-in-an-avi-is-cut-into-whole-frames.md)); before, every such file failed at 0 ms |
| 1337x | Its results carry only Prowlarr's download link; the magnet is asked for when one is chosen ([ADR 89](../adr/0089-a-result-without-a-magnet-is-resolved-when-chosen.md)); before, none ever reached the app |
| Torrentio beside Prowlarr | `Torrentio`: Cinemeta finds the film or series, Torrentio lists its torrents in ~0.3 s; one more source in `ProwlarrSearch.updates`, the same info hash listed once ([ADR 86](../adr/0086-torrentio-beside-prowlarr.md)) |
| Add a magnet, wait for metadata | `prepare` — polls for the file list, because asking once returns nothing |
| Season packs as episodes | `TorrentEpisodes` + `TorrentPlayables.queueItems` — one queue item per playable file |
| Playback | **no new playback code**: a TorrServer stream is an ordinary ranged HTTP URL, so `MediaItem.mediaUrl` and the one `PlaybackController` take it unchanged |
| Listen mode | the server's remuxed audio (HLS), 2.1 MB/min against 15.2 measured |
| Offline | the same `routeNow` decision as every other pillar; a downloaded copy wins over the stream |
| Zero config | the host is baked in at build time from `TOTUM_HOME_SERVER` (a CI secret, never committed); signing in is one tap, and the token and Prowlarr key arrive together on a `totum://auth` deep link |
| Who may use it | a token per device, sent only as a header, checked by `server/totum-auth`; links carry no secret; sign out per device ([ADR 90](../adr/0090-a-token-per-device-and-none-in-links.md)) |

Nothing above the `HomeTorrentServer` port knows a torrent is involved. That is the point: the
pillar reaches the UI as one more `SearchHit`, one more queue item, one more thing that plays.

## Trust badges (2026-10-30)

Dewi: *"in Pirate Bay there's like green skulls and pink skulls … Can we put something similar in
ours? … general."* Every result now carries chips that say whether it looks trustworthy
(`TorrentTrust` in `:core:data`, shown by `TrustChips` under the subtitle; ADR 101):

| Chip | From |
|---|---|
| `💚 VIP uploader (name)`, `💗 Trusted uploader`, `👤 Ordinary uploader`, `❔ Uploader unknown` | The Pirate Bay's own API (`Apibay`, `apibay.org/q.php`), asked beside Torrentio on every search; its results merge with Prowlarr's by info hash, and the merge keeps the strongest standing from any copy. Prowlarr's TPB definition drops the status (every result reads `indexerFlags: ["freeleech"]`) |
| `💚 Official YTS release` | the YTS indexer, through Prowlarr or Torrentio |
| `🏷️ AMIABLE release` | a short built-in list of known release groups, read from the name |
| `🌱 Well seeded (104 seeding, 15 leeching) · 8 year(s) old`, `🌿 Some seeders`, `🥀 Few/No seeders` | seeders, and Prowlarr's or apibay's leechers and date (now parsed) |
| `⚠️ Looks like a cinema recording (HDCAM)`, `⚠️ Looks fake: 80 MB for 1080p`, `⚠️ Its name mentions a program (.exe)` / `a password` | red flags from the name and size; a flagged result is listed after the others, before the cap of eight |

Once a torrent is prepared, a program among its files (`.exe`, `.scr`, `.msi` …) is named in the
result toast ("⚠️ It also holds 1 program(s) (…); only its video is played"); its video still plays.
A 1337x result with no magnet has no info hash until chosen, so it reads `❔ Uploader unknown`.
The trail logs `trust of N result(s) …: uploaders VIP=…; K flagged and sorted down: …` per update and
`apibay "<query>" -> N result(s) … [uploaders: …]` per search. Measured on the emulator
(2026-10-30, "night of the living dead 1968"): apibay answered in 395 ms with 48 results, and all 48
Pirate Bay results took a standing (24 VIP, 9 trusted, 15 member).

## What you've watched, and quality (2026-10-30)

Dewi: *"Have I viewed some of the video before … multiple videos in a folder. Have I viewed one? Am I
half in the middle of one? … I trust you."* (The choices below are Claude's, on that delegation.)

- **Progress on every result** (`TorrentProgress.chips`, read per row through `LocalTorrentProgress`):
  a torrent file is the item `torrent:<info hash>:<file index>`, so its saved position is matched by
  the result's hash: `✅ Watched`, `▶️ 34 of 96 min`, or for a pack `✅ 3 of 10 watched` and
  `▶️ S01E04: 12 min in`. A different copy of something played (matched by the name's words up to
  its year or episode, plus that) says `👀 You've played another copy of this`; that is how a 1337x
  result, which has no hash until chosen, is still recognised.
- **What has been opened is remembered** (`TorrentsSeen`, `files/torrents-seen.json`, the latest
  500): hash, name and each playable file's label, written when a torrent is prepared, so a pack's
  progress can name the episode and count the whole.
- **Tapping resumes** (`TorrentProgress.resumeAt`, `PlaybackQueue.playAll(startAt)`): the queue is
  the whole torrent in order as before, but play starts at the file in progress, else the first
  unwatched after the last watched, else the first; logged as `starting at file N of M (…) — W
  watched, S started`.
- **The episode picker** marks each file `✅ watched` or `▶️ 12 min in` beside its download state.
- **Quality chip** read from the name: resolution, source, codec, HDR / Dolby Vision, Atmos and
  5.1/7.1, e.g. `🎞️ 4K · WEB · HEVC · Dolby Vision · Atmos · 5.1`.
- Red flags, quality and the release group are read only from the release's tail (after the year,
  episode or quality marker), so a title's own words ("The Bat", "Hall Pass", "Cam", "Charlotte's
  Web") raise nothing; a single episode's size floor is a quarter of a film's (ADR 101, after the
  batch review).

Seen on totum-api35 (2026-10-30): the YTS copy of a public-domain film read `▶️ 2 of 96 min` after
two minutes' play, the 1337x copy of the same film `👀 You've played another copy of this`.

## Speed (measured 2026-10-08, emulator against the Pi)

- **Search asks each indexer on its own, in parallel, and streams** (`ProwlarrSearch.updates`,
  `SearchSource.updates`): the section shows the first results as soon as one indexer has any, and
  each later indexer's results are added when they arrive, with "Still searching 1337x…" until the
  last answers or 2 minutes pass (Dewi, 2026-10-08: "maybe let it get added once it arrives"). 1337x sits behind a Cloudflare check that
  FlareSolverr took 83.6 s to pass, and Prowlarr waits for every indexer, so one search took 86 s;
  the app's 20 s limit could not cut it because the HTTP call ignored cancellation, and the
  section then said "Can't reach your home server". Now calls are cancellable (`Call.await`), a
  slow server says it is slow, and first results took 0.5-2 s (Big Buck Bunny, Sintel, Elephants Dream).
- **Opening a torrent warms the first file's first and last megabyte** at once (`warmVideo`). An
  MKV keeps its index at the end; measured cold on the Pi, the first 1 MB took 1.2 s and the last
  5.7 s, and the player reads them one after the other. Player ready after `play()`: 6.6-8.0 s
  before, 4.6-5.1 s after (three cold runs each, Sintel 1080p, 22 seeders).
- **Pi tuning was tried and reverted** (Dewi approved trying it): TorrServer at 100 connections, a 512 MB cache
  and a live tracker list made the cold last-MB fetch 3.6-4.4 s against about 1 s on the original 25
  connections / 256 MB / 14 trackers, in alternating runs, likely because `PreloadCache` (50%) preloads
  half the cache on every add. Settings restored; both are saved on the Pi as
  `~/torrserver-settings-{backup,tuned}-20261008.json`.
- Big Buck Bunny has 0-1 seeders on these indexers and never produced a file list in 30 s; that is
  the swarm, not the app.
- Logged: `search … per indexer: <name> <ms> <n> result(s)…; left out: [...]`, `search … in Nms
  [top seeders=N]`, `prepared … in Nms (added in Nms)`, `warmed the start|end of …: HTTP 206, N bytes in Nms`.

## What it deliberately does NOT do

- **No torrent client on the phone.** Torrenting is mostly UDP (µTP, DHT, UDP trackers) and a
  SOCKS5 proxy handles UDP badly, so an in-app client behind a proxy would have had few peers and
  magnets that barely resolve. The Pi does it inside gluetun behind PureVPN's kill-switch instead,
  and the phone↔Pi channel is plain HTTP — which is exactly what the existing Google gate can
  protect. Full reasoning, including two reversed decisions, in
  [`../todos/public-domain-film-tv.md`](../todos/public-domain-film-tv.md).
- **No automatic download of films.** The queue's automatic fetch exists to make the queue
  listenable and a torrent has no audio-only form to fetch (the server's audio is a live HLS
  playlist), so an `audioOnly = true` request quietly fetched the whole film — proven on a device
  2026-08-06, `copy=full`. Films are left for a deliberate tap and the queue banner says
  `2 to download by hand` rather than promising a fetch that is never coming.
- **No transcoding.** The Pi uploads at 65 Mbps measured, six times the headroom a 1080p x264 rip
  needs, so search is biased toward phone-safe releases instead. The real gap is codecs, not
  bandwidth: DTS and TrueHD cannot be decoded by Android at all.

## Proven where

- **In CI, every commit** — `TorrentQueuePlaybackTest`: search → prepare → queue → stream, and
  downloaded → radios off → plays from the file. Against a stand-in speaking Prowlarr's and
  TorrServer's protocols, with media this repo generates and public-domain titles. No magnet is
  resolved and no peer is contacted.
- **On the real Pi, by hand** (2026-08-01) — a 206 range request halfway into a 1.74GB film served
  in 3.3s against 3.6s at the start; torrent traffic asserted to leave via PureVPN, not the home IP;
  the gate refusing unauthenticated requests by content, not status code.
- **Not covered:** Listen mode's HLS audio over the wire (a stand-in cannot serve it honestly), and
  a full phone-to-Pi run (install → sign in → search → tap → watch), which needs the real device and
  is still owed.

## Listen and Watch (2026-10-22)

A torrent of a video file offers Listen ⇄ Watch like a YouTube video, on its row, its menu and in the player; a torrent
of an audio file offers neither. Listen plays the home server's audio-only remux. The rule is the item's picture, not
its pillar ([ADR 70](../adr/0070-listen-and-watch-follow-the-picture-not-the-pillar.md)); before it, torrents had no
switch at all because they ride the podcast handle.
