---
title: Torrentio is searched beside Prowlarr
kind: adr
status: accepted
updated: 2026-10-24
---

# 86. Torrentio is searched beside Prowlarr

- Status: Accepted
- Date: 2026-10-24

## Context

Report 0.1.707 (2026-10-24, "torrent search didn't work???"): the torrent section waited on indexers that took 21 s
(The Pirate Bay, a one-off) and 89 s (1337x behind Cloudflare). Dewi asked for faster torrent search and was open to
changing anything in the stack, and chose "prototype it beside Prowlarr" for a Stremio-style addon. Torrentio lists
the torrents for a film or an episode by its IMDb id; Cinemeta, Stremio's catalogue, turns a title into that id.
Measured from the Pi: Cinemeta 0.08 s, Torrentio 0.08-0.1 s (42 streams for a film, 49 for an episode).

## Decision

- `Torrentio` (`:core:data`) is one more source in `ProwlarrSearch.updates`, beside each Prowlarr indexer: it
  takes part in "still searching …" and the merge, and a source that fails does not hide the others' results.
- It asks Cinemeta's movie and series catalogues in parallel and takes, from each, the first entry whose name holds
  every word of the search; a film lists `stream/movie/<id>`, a series `stream/series/<id>:<season>:<episode>`,
  the episode taken from an `SxxEyy` in the search and S01E01 otherwise.
- A stream becomes a result only with a 40-character info hash; its magnet is built from the hash and the release
  name (`magnetFor`, shared with Prowlarr results that carry only a hash, such as YTS's). Seeders, size and the
  site come from the stream's last title line; the indexer reads "Torrentio · <site>".
- The same torrent from two sources (the same info hash) is listed once, with the most seeders.
- The phone asks Cinemeta and Torrentio itself, not through the Pi: search stays on the home IP at home, as Dewi
  chose for Prowlarr, and needs nothing deployed.

## Consequences

- Films and series answer in about 0.3 s on the emulator (42 streams for the 1968 *Night of the Living Dead*), with
  sites Prowlarr does not reach; generic searches (not a film or series name) still rely on Prowlarr.
- Away from home the Prowlarr indexers fail but Torrentio still answers; playing still needs the home server.
- Two public services the app does not run; when they are down the source fails and the others carry on.
- Tests use hand-written public-domain fixtures (`core/data/src/test/resources/torrentio/`), never live responses.
