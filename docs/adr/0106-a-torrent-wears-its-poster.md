---
title: A torrent wears its poster, from its site or its IMDb id
kind: adr
status: accepted
updated: 2026-10-31
---

# 106. A torrent wears its poster, from its site or its IMDb id

- Status: Accepted
- Date: 2026-10-31

## Context

Torrents had no picture anywhere: a film icon in search, a blank square in the queue, the mini player and the
notification. Dewi: *"it would be good if we can have like pictures associated with the torrent … throughout the
app."* What the sources give, measured on 2026-10-31: AudioBook Bay a cover on every result (45/45), YTS a poster
and an IMDb id, Prowlarr's Pirate Bay an IMDb id on about half (52/100), apibay an `imdb` on 37/100, 1337x and
Knaben nothing. Torrentio results come from a Cinemeta match, so the IMDb id is known for all of them.

## Decision

- `TorrentResult` carries `posterUrl` (the site's own, Prowlarr's `posterUrl`) and `imdbId` (Prowlarr's `imdbId`
  number as `tt%07d`, apibay's `imdb`, Torrentio's catalogue match). Its `artworkUrl` is the poster, else
  `images.metahub.space/poster/medium/<tt>/img` (the poster service Stremio uses; no key).
- Merging two copies of one torrent keeps whichever has them.
- A result with neither borrows from another result of **the same work** in the same search: the same title words
  and year (a film), or the same title words before an episode marker (a show). A name with neither a year nor an
  episode borrows nothing, since "Dune 1080p" could be either Dune.
- The hit's `artworkUrl` is the poster; the search row shows it (48×72) and keeps the film or headphones icon in
  a box of the same size when there is none. Queued files get it as `MediaItem.thumbnailUrl`, so the queue, the
  player, the mini player and the notification show it through the paths every other item uses.
- No extra request per result: no title search to find a poster. Each search logs `posters: N from the site,
  N by IMDb id, N without`; preparing a torrent logs the artwork it queued with.

## Consequences

- About a tenth of film results still have no picture (18 of 250 over one search's updates on 2026-10-31).
  A Cinemeta title lookup could close that at the cost of a request per search; not done.
- Borrowing can be wrong when two different works share a title and a year; the cost is a wrong picture,
  never a wrong file.
- Torrents queued before this change keep no picture until queued again.
