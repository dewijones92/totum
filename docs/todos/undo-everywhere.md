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

A playlist Undo puts the item back in its old slot (`LocalPlaylistStore.insertItem(id, item, at)`; Room shifts the later
positions up by one). Tests to add: `PendingDownloadDeletes` (hide, undo, window end, start-up recovery, locked), the
helper's no-host and nothing-removed branches, `AskViewModel.forget`/`unforget`,
`LocalPlaylistDetailViewModel.removeAll`/`restore`, Room `insertItem` (androidTest).

## Whole-playlist delete and History clear (2026-11-06, third pass)

Decided by the coordinator; Dewi delegated it ("your call on each", 2026-11-06): both offer ↩️ Undo instead of a confirm
dialog or a hold, through the same helper. The playlist list's bulk Delete and the playlist page's Delete go through
`PlaylistRemovals`, the History header's Clear all through `HistoryClearing`; the stores return what they removed
(`RemovedPlaylist`, the entries) and take it back (`restore`), and the snapshot is held in memory for the snackbar's
window. Details and the limit (a process death inside the window loses it) are in
[ADR 128](../adr/0128-undo-already-queued-and-not-for-me-share-the-queue-entry-seam.md). Tests: see
[tests/_index](../tests/_index.md); owed: the playlist page's Delete → Undo on a device, and a screenshot of the snackbar.

