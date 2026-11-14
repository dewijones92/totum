---
title: Undo after every remove
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07: PendingDownloadDeletesTest, AskChatUndoTest, LocalPlaylistDetailUndoTest and the Room/store tests; playlist-page Delete then Undo and the snackbar not covered or seen on a device)
batch: A (2026-11)
priority: medium
updated: 2026-11-14
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
positions up by one).

## Whole-playlist delete and History clear (2026-11-06, third pass)

Decided by the coordinator; Dewi delegated it ("your call on each", 2026-11-06): both offer ↩️ Undo instead of a confirm
dialog or a hold, through the same helper. The playlist list's bulk Delete and the playlist page's Delete go through
`PlaylistRemovals`, the History header's Clear all through `HistoryClearing`; the stores return what they removed
(`RemovedPlaylist`, the entries) and take it back (`restore`), and the snapshot is held in memory for the snackbar's
window. Details and the limit (a process death inside the window loses it) are in
[ADR 128](../adr/0128-undo-already-queued-and-not-for-me-share-the-queue-entry-seam.md). Tests: see
[tests/_index](../tests/_index.md); owed: the playlist page's Delete → Undo on a device, and a screenshot of the snackbar.

## Tests (Batch A testing stretch, part A, 2026-11-07)

JVM: `PendingDownloadDeletesTest` (hidden at once and everywhere, the file kept until the window ends, undo inside
the window, a later batch waiting for the earlier window, a locked item, start-up recovery, a new download taking a
pending delete back, an immediate delete), `AskChatUndoTest` (forget, undo in date order, an unknown chat, undo twice),
`LocalPlaylistDetailUndoTest` (several items out and back into their slots, nothing offered for a missing item),
beside `PlaylistRemovalsTest`, `HistoryClearingTest`, `RoomUndoTest` and `LocalPlaylistStoreTest`. Each seen failing
with the code under it broken (undo made inert, the queued position changed, forget saving the wrong list, the playlist
slot ignored).

Still owed: the helper's own no-host and nothing-removed branches and the snackbar itself (`rememberRemoveWithUndo`
is a composable; it needs a Compose host with a `SnackbarHostState`), and the playlist page's Delete then Undo on a
device with a screenshot.
