---
title: Shuffle all across several sources at once
kind: todo
status: partly done 2026-10-22
area: queue/shuffle
priority: high
requested: 2026-10-22
updated: 2026-10-22
---

# Shuffle all across several sources at once

Select several artists, channels, podcasts or playlists on any list (search, Library, subscriptions), then "🔀 Shuffle all of these" makes one shuffled group from every item of every one (Dewi's choice). `ShuffleTarget` already takes several sources (a channel group chip uses it); `ArtistShuffle` takes one.

Partly done 2026-10-22: Library → Subscriptions selection has "🔀 Shuffle all of these" (`shuffleTargetOf`, `rememberShuffleStarter`), and the queue's selection bar has "🔀 Shuffle together" for groups already queued. Still to do: search results, artists (`ArtistShuffle` takes one), playlists.
