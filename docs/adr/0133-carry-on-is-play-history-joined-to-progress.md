---
title: Carry on is play history joined to progress
kind: adr
status: accepted
updated: 2026-11-06
---

# 133. Carry on is play history joined to progress

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06 (batch A): half-watched items of every kind, newest first, at the top of Library. The progress store
knows how far into each item playback got but has no titles or pictures (`observeStates` is a map of id to
`PlayState`), and the account's watched positions are ids only. The play history holds whole `PlayableItem`s.

## Decision

- **The row is built from the history and the one play-state map the lists already read.** `carryOn(history, stateOf)`
  (`:core:domain`, `CarryOnItem.kt`) walks the history in its own order (most recent first), keeps an item whose state
  `MediaFilter.IN_PROGRESS` accepts, leaves out one that `isNearlyFinished` (the same rule the rest of the app uses
  for "done"), and stops at 12. The play-state map is `LocalPlayStates`, the account-merged one, so a card's bar is the
  bar every row shows for the same item.
- **Music and live streams are left out.** A half-heard song is not something to carry on, and a live item never
  resumes from a saved place (ADR 69). Everything else (videos, podcasts, audiobooks, torrents) is included.
- **It is a Library hub section** above the group tiles, built like Pinned and Recently (a `LazyRow` of cards).
  A tap plays the item through `PlaybackQueue.playAll`, as the History page does, which inserts it after what is playing
  and resumes where it was left; a long press opens the ordinary item menu (`ItemActionSheet`), so a card offers the same
  actions as a row.
- **Items marked 🙅 Not for me are left out** (`NotForMe.without`, read through `CarryOnViewModel`): the row suggests, so
  it follows the one suppression list (ADR 128).

## Consequences

- An item played long ago that has dropped out of the capped history is not shown, even if it has progress; progress
  without a title has nothing to show.
- An item watched elsewhere on the account (a position with no history row here) is not shown for the same reason.
- The row is the first place the title display policy (the planned spoiler shield) will have to cover: it reads
  `item.title` and the thumbnail directly, like the rows do today.
