---
title: Offline playable is whatever routeNow plays from the device
kind: adr
status: accepted
updated: 2026-11-06
---

# 132. Offline playable is whatever routeNow plays from the device

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06 (batch A): "✈️ N playable now" on the queue's offline summary, and a way to show just those. The
queue already said "unavailable offline" on a row, by `unavailableOfflineNow(state, offline)`, which asked only whether
the download state was `Downloaded`. The queue itself, when it plays, asks `PlayableItem.routeNow`, which also plays
a handle that carries its own file (a `LocalVideo` or a `Podcast` with a path). So a row could say "will be skipped"
for something the queue would play, and any new count built on the row's rule would have inherited the gap.

## Decision

- **One definition.** `PlayableItem.playsWithNoSignal(state)` (`:core:domain`, `OfflineReadiness.kt`) asks `routeNow`
  with `offline = true` and says yes when the answer is a file route. `unavailableOfflineNow` is now
  `offline && !playsWithNoSignal(state)` and an extension on the `PlayableItem`, so a row cannot answer from the
  state alone any more. The same answer is the chip's count, the filter, and a `downloads.queuePlaysWithNoSignal`
  line in diagnostics.
- **The chip is a filter.** The queue's offline summary carries a `FilterChip`: "✈️ N playable now" while there is no
  network, "✈️ N play with no signal" while there is one (nothing is unplayable while online, so "now" would mislead).
  Selecting it sets the queue's ordinary item filter to the new `DownloadedFilter.PLAYS_OFFLINE`, so it behaves like
  every other filter (the queue shows a flat filtered view, Clear filters undoes it, the sort and filter sheet shows
  it ticked).
- **The filter is a fourth choice in the sheet's "On the phone" row, in every list.** For a list of plain items it
  equals "Downloaded" (a bare `MediaItem` has no handle of its own), which is the price of one filter vocabulary; only
  the queue passes its `PlayableItem`s to `filteredByItems`, where the answer can differ.

## Consequences

- `ItemFilters.accepts` takes the extra answer with a default of `downloaded`, so callers that do not know a handle
  behave as before.
- Playing from the Pi (`atHome`) is not offline: `routeNow` only takes it while online, so a Pi copy never counts.
- The "audio-only copies" the todo mentioned already counted (an audio-only `Downloaded` is a file route offline);
  the real gap was a handle's own file.
