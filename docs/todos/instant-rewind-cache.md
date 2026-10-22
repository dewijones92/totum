---
title: Rewind without buffering, for everything
kind: todo
status: requested 2026-10-22, decided
area: playback
priority: high
requested: 2026-10-22
updated: 2026-10-22
---

# Rewind without buffering, for everything

A disk cache of what was just played, for every stream (videos, podcasts, torrents), so jumping back is instant (Dewi, 2026-10-22: "for anything"). About 1 GB, least recently used evicted. Media3 `SimpleCache` + `CacheDataSource` under the existing data-source stack; live segments and SABR need care.
