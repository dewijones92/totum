---
title: Torrent results say whether they look trustworthy
kind: todo
area: torrents
priority: high
status: shipped 2026-10-30 (ADR 101, docs/features/torrents.md)
updated: 2026-10-30
---

# Torrent results say whether they look trustworthy

Dewi, 2026-10-30: *"in Pirate Bay there's like green skulls and pink skulls … they denote something
like if the torrent is safe … Can we put something similar in ours? Not just specific to Pirate Bay,
but … general."*

## Found

- TPB's skulls are the **uploader's** standing: green VIP, pink trusted. Prowlarr's Cardigann TPB
  definition drops it: every TPB result carries only `indexerFlags: ["freeleech"]` (checked live on
  the Pi, 51 results for a public-domain film).
- TPB's own API does carry it: `https://apibay.org/q.php?q=…` returns `status` (`vip`, `trusted`,
  `member`), `username`, `num_files` and `info_hash` per result (48 results, 24 vip, 9 trusted,
  15 member for the same film), so it matches our results by info hash.
- Prowlarr also returns `leechers`, `publishDate`/`age`, `files` (a count) and `infoUrl` for every
  result, which the parser ignores today.

## Decided (Dewi, 2026-10-30)

- **Signals: all four.** Uploader status (TPB VIP/trusted from apibay; YTS counts as an official
  uploader; other indexers say "unknown"), red flags (CAM/TS/telesync in the title, a size far too
  small for the quality, and once prepared no playable video or an executable in the files), health
  (seeders against leechers, age), and a small built-in list of known release groups read from the
  title.
- **Look: a words chip** with an emoji, e.g. `💚 VIP uploader`, `💗 Trusted uploader`,
  `❔ Unknown uploader`, `⚠️ Looks fake: 80 MB for 1080p`.
- **Red flags: mark and sort down** — visible with the warning, listed below the others.
- **Lookup: the phone asks apibay**, one call per search beside the other sources; slow or down
  means "unknown", never a failed search. No Pi change.
