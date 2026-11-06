---
title: Play history
kind: feature
status: shipped
area: library
updated: 2026-11-06
---

# Play history

A "Recently played" list across both pillars, in Library → History. Most-recent
first, tap to replay, Clear all.

**Clear all offers ↩️ Undo, not a confirm** (2026-11-06, the coordinator's call; Dewi delegated it, ADR 128). The old hold
to confirm is gone. `PlayHistoryStore.clear()` returns the entries it removed (most recent first) and `restore(items)` puts
them back in their old order **behind** anything played since, skipping items already present and never past the cap
(Room gives each restored entry a timestamp just older than the oldest entry still there). The snackbar's window is long;
Undo runs on the application scope, so it works after leaving the page. The snapshot is held in memory (`HeldRemoval`),
so a process death inside the window loses the undo. Code: `ui/history/HistoryClearing.kt`.

## Seam

`PlayHistoryStore` (port, `:core:data` `data/history/`) — one store for both
pillars. It reuses `PlaylistItem` (a `MediaItem` + a `PlaylistPlayback` handle),
the same shape the local-playlist store persists, so a history entry survives
stream-URL expiry: a video keeps its stable watch URL, a podcast its enclosure.

- Fake: `InMemoryPlayHistoryStore` (dedup-by-item, move-to-front, capped at 50).
- Impl: `RoomPlayHistoryStore` over `PlayHistoryDao` / `PlayHistoryEntity`
  (`play_history`, DB v9). Rows denormalize the display fields + play handle.
- Wired in `AppContainer.playHistoryStore`.

## Recording (one entry per pillar, no double-count)

- **Videos** are recorded in `VideoPlaybackLauncher.play()`, against the stable
  watch URL — so a replay re-resolves through the launcher (streaming URLs expire).
- **Podcasts** are recorded via a `Media3PlaybackController.onPlay(item, kind)`
  callback, guarded to `kind == PODCAST`. All podcast plays go through the
  controller and their enclosure URL is stable. Videos reach the controller as
  `kind == VIDEO` (quality switches, Listen mode) and are skipped there — the
  launcher owns them — so nothing is recorded twice.

## Persistence detail (DRY)

`PlaylistItemColumns` (interface, `:core:database`) is the shared denormalized
column contract; both `LocalPlaylistItemEntity` and `PlayHistoryEntity` implement
it, and `playlistItemFrom(columns)` is the single mapper back to a `PlaylistItem`.

## Files

- `core/data/.../data/history/PlayHistoryStore.kt`, `fake/InMemoryPlayHistoryStore.kt`
- `core/database/.../PlayHistoryEntity.kt`, `RoomPlayHistoryStore.kt`, `PlaylistItemColumns.kt`
- `app/.../ui/history/PlayHistoryViewModel.kt`, `PlayHistoryScreen.kt`
- `app/.../ui/library/LibraryScreen.kt` (History nav entry)
- `app/.../video/VideoPlaybackLauncher.kt`, `core/playback/.../Media3PlaybackController.kt` (recording hooks)

## Tests

`PlayHistoryStoreTest` (order / move-to-front dedup / cap / clear, and clear-then-restore: order, newer plays stay on
top, no duplicates, the cap), `HistoryClearingTest` (the Undo path through the held snapshot), `RoomUndoTest`
(`:core:database` instrumented: the Room side of restore; written, not yet run on a device). Verified
on-device: play a video and a podcast → both appear under Library → History,
most-recent first; tap replays; Clear all empties it.

The history also feeds the Library's ▶️ Carry on row (2026-11-06): the half-watched items in it, newest first, joined to
the play-state map ([carry-on-row.md](carry-on-row.md), ADR 137).
