---
title: Yours in search
kind: todo
status: built
batch: C (2026-11)
priority: medium
updated: 2026-11-20
---

# Yours in search

Dewi, 2026-11-20 17:29: *"I am trying to search for my film that I downloaded … there's no easy way to … filter by like
oh, these are things I've downloaded. I want to only see the ones I've downloaded."* Built as the 📥 Yours tab:
[ADR 183](../adr/0183-search-shows-what-you-already-have-first.md), [search-sections](../features/search-sections.md).

## Owed

- **Deploy the Pi** (`totum serve`) for the 🎞️ part; until then the tab says "⚠️ The home server needs updating to search Jellyfin".
- The Music scope's search has no Yours tab (a downloaded song is not offered there): decide.
- Series are not searched (nowhere to open one from a search row).
- Queue and pinned items are not searched on purpose (6,033 queue rows); one more `YourPlace` if wanted.
- "Downloaded" on list headers stays phone-only; a 🏠 At home value for it is a separate change if wanted.
- A Jellyfin episode from a search row plays alone, not with the rest of its season.
