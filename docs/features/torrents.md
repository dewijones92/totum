---
title: Torrents (public-domain film & TV)
kind: feature
status: shipped
area: torrent
updated: 2026-10-08
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
| Add a magnet, wait for metadata | `prepare` — polls for the file list, because asking once returns nothing |
| Season packs as episodes | `TorrentEpisodes` + `TorrentPlayables.queueItems` — one queue item per playable file |
| Playback | **no new playback code**: a TorrServer stream is an ordinary ranged HTTP URL, so `MediaItem.mediaUrl` and the one `PlaybackController` take it unchanged |
| Listen mode | the server's remuxed audio (HLS), 2.1 MB/min against 15.2 measured |
| Offline | the same `routeNow` decision as every other pillar; a downloaded copy wins over the stream |
| Zero config | the host is baked in at build time from `TOTUM_HOME_SERVER` (a CI secret, never committed); signing in is one tap, and the token and Prowlarr key arrive together on a `totum://auth` deep link |

Nothing above the `HomeTorrentServer` port knows a torrent is involved. That is the point: the
pillar reaches the UI as one more `SearchHit`, one more queue item, one more thing that plays.

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
