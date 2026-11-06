---
title: Undo after every remove
kind: todo
status: built
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Undo after every remove

Dewi 2026-11-06. One shared helper from `QueueUndo.rememberRemoveWithUndo` (the alarm screen's copy folds into it);
covers queue, downloads (a deferred delete, since the file goes), playlists, Ask chats; group delete keeps its hold to
confirm.

Whole-batch review: move `rememberRemoveWithUndo` from `ui/queue` (internal) to `ui/common` first; the alarm screen's copy folds in.

## Built 2026-11-06

`rememberRemoveWithUndo` now lives in `ui/common/SnackbarOffers.kt` and serves the queue, the alarms page, Ask's
earlier chats and (through one shell collector) download deletes (`PendingDownloadDeletes`, deferred 12 s, pending ids
persisted). Playlist item removes offer Undo too.

Open: a playlist Undo puts the item back **at the end**, because `LocalPlaylistStore.addItem` only appends. Keeping the
place needs `removeItem` to return the slot and `addItem` to take one (Room, the in-memory store, `MultiSelectTest`'s
wrapper). Tests to add: `PendingDownloadDeletes` (hide, undo, window end, start-up recovery, locked), the helper's
no-host and nothing-removed branches, `AskViewModel.forget`/`unforget`, `LocalPlaylistDetailViewModel.removeAll`/`restore`.
