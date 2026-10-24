---
title: A torrent result without a magnet is resolved when it is chosen
kind: adr
status: accepted
updated: 2026-10-24
---

# 89. A torrent result without a magnet is resolved when it is chosen

- Status: Accepted
- Date: 2026-10-24

## Context

1337x's results reach the app with no magnet: `guid` is the torrent's page, and the magnet is on that page, which
Prowlarr fetches only when its own `downloadUrl` (`/<indexer>/download?apikey=…&link=…`) is asked for. The parser
kept only results with a magnet or an info hash, so every 1337x result was dropped: across every report on the Pi,
1337x answered "0 result(s)" 18 times and never more, while it found 40 for the same searches. Asking Prowlarr for
each result's magnet during the search would mean 40 page fetches through Cloudflare per search.

## Decision

- A result carries a `TorrentLink`: `Magnet(uri)`, or `ViaIndexer(path)`, the download path with the API key taken
  out. The magnet is preferred, then a hash, then the indexer's link.
- `HomeTorrentServer.prepare(link)` asks Prowlarr for a `ViaIndexer` link (key in the `X-Api-Key` header, redirects
  not followed) and adds the magnet its `Location` names; no magnet is a failed prepare, logged with the status.
- Results via an indexer are merged by their path, since their hash is not known until chosen.

## Consequences

- 1337x reaches the app (19 results for "night of the living dead 1968" on the emulator); choosing one adds ~0.3 s
  (310 ms measured, 1.8-4.3 s on the Pi without a held clearance) before the torrent is added.
- A 1337x result and the same torrent from another source can both be listed.
- Any indexer whose download link redirects to a magnet now works the same way.
