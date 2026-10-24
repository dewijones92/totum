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
