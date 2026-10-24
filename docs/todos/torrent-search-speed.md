---
title: Torrent search answers fast and never gives up early
kind: todo
status: decided 2026-10-24, in progress
area: search/torrents/pi
priority: high
requested: 2026-10-24
updated: 2026-10-24
---

# Torrent search answers fast and never gives up early

Report 0.1.707 (2026-10-24 05:40, "torrent search didn't work???"): the search screen gave the torrent section
20 s to say anything, while The Pirate Bay answered at 21 s (a one-off; it is usually 0.3-3 s) and 1337x at 89 s
(Cloudflare's challenge solved by FlareSolverr; ~8 s once a clearance is held). YTS answered at once with two
unrelated films and no magnet. Dewi: *"display them as they come … find the ways to speed up torrent search …
free to change anything in our stack"*.

Decided with Dewi, 2026-10-24:

- **Search stays on the home IP** (Prowlarr and FlareSolverr on `bin_private`); only the swarm (TorrServer,
  totum-audio) goes through PureVPN. Measured: Prowlarr/FlareSolverr exit as the home address, TorrServer and
  totum-audio as PureVPN's.
- **The torrent section says at once which indexers it is waiting for**, so the 20 s watchdog never fails it;
  results arrive as each indexer answers (shipped first).
- **Keep 1337x's Cloudflare clearance warm**: a Pi timer runs a tiny search every ~15 min.
- **Warm when search opens**: opening the search box starts the solve.
- **1337x: fewer pages** (best-seeded first).
- **More fast indexers** (EZTV for TV and similar), searched in parallel.
- **Prototype a Stremio-style addon (Torrentio) beside Prowlarr**: torrents by film/episode in ~1-2 s.
- Ship each change as its gate is green; soak and review at the end.

Progress 2026-10-24:

- ✅ The torrent section says at once which indexers it is waiting for (bb4276fc). Emulator: "the office" showed
  "still waiting" at 420 ms and The Pirate Bay's results at 1.4 s.
- ✅ 1337x: two pages, best-seeded first (`server/prowlarr/definitions/1337x-fast.yml`): 4.7 s warm, 40 results.
- ✅ A Pi timer keeps the Cloudflare clearance warm (`server/prowlarr/search-warmer.*`, every 15 minutes).
- ✅ Opening search warms the FlareSolverr-backed indexers, at most once in 10 minutes (`HomeTorrentServer.warmSearch`);
  emulator: "warmed 1337x in 3689ms".
- ✅ YTS results (no magnet, an address ending in the info hash) get a magnet built from the hash.
- ✅ Torrentio beside Prowlarr ([ADR 86](../adr/0086-torrentio-beside-prowlarr.md)). Emulator, *Night of the Living
  Dead*: Torrentio 295 ms, 42 results; YTS 458 ms; The Pirate Bay 831 ms; 134 results after 18 duplicates merged.
- ❌ More Prowlarr indexers: EZTV is blocked by the ISP (HTTP 451); LimeTorrents and Torrent Downloads answer fast
  but carry no magnet or info hash. kickasstorrents.ws, left behind by the trial, failed and is disabled.
- 🔍 **1337x has never reached the app**: its results carry no magnet (only a page address and Prowlarr's download
  link), so every one was dropped. Across every report on the Pi it answered "0 result(s)" 18 times and never more.
  Next: resolve the magnet through Prowlarr's download link when a result is chosen.
