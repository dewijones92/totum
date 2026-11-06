---
title: Undo, already-queued prompts and not-for-me share one snackbar and one queue seam
kind: adr
status: accepted
updated: 2026-11-06
---

# 128. Undo, already-queued prompts and not-for-me share one snackbar and one queue seam

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06 (batch A): "undo after every remove", "already queued at #N, move it up?" and "🙅 Not for me". Undo
existed twice (the queue's `rememberRemoveWithUndo` and the alarm screen's own copy), only the Queue tab and the alarms
page had a snackbar at all, adding something already queued either moved it silently (`enqueue`, `playNext`) or skipped
it silently (`addToGroup`), and nothing remembered "I do not want this".

## Decision

- **One undo helper, one snackbar.** `rememberRemoveWithUndo` lives in `ui/common/SnackbarOffers.kt`: it is given what to
  remove, how to describe it and how to put it back. The shell hosts one snackbar over every tab and overlay
  (`LocalSnackbarOffers`); the Queue tab and the alarms page keep their own host (they are tested alone) and hand it to
  the same helper. Group delete keeps its hold to confirm.
- **A download delete is deferred, not undone.** `PendingDownloadDeletes` (outermost `DownloadManager`, entered through
  `deleteSoon`) hides the item from every download flow and removes the file 12 s later; the pending ids are persisted so
  a dead app finishes the delete at the next start. Clean-up cards, failed rows and "clear failed" stay immediate: they
  are already confirmed.
- **The already-queued rule is on the queue's entry seam.** `enqueue` and `playNext` read the queue before changing it and
  say `QueueNotice.AlreadyQueued` instead of moving; one collector shows "Move up" (`moveUp`: to play next, group kept).
  Bulk adds keep their dedup and say `N already queued` once; background top-ups (`byYou = false`) never do.
- **Not for me is one suppression list** (`NotForMe` in `:core:data`, its own file, in the Drive backup) keyed by canonical
  item id, read wherever the app chooses candidates for you: Explore, the Related tab, the Videos feed, group pages,
  `LatestUploads` (new uploads and group news), radio, shuffle all, artist shuffle and Ask (titles joined to the "have"
  list the AI is told to avoid, and matching suggestions dropped). Pages you opened yourself (a channel, a podcast,
  search, a playlist, the All subscriptions list) still show everything. The menu action
  removes the item from the queue and records it, with an Undo that does both back. Settings → 🙅 Not for me lists and
  allows items again.

### Second pass, 2026-11-06 (consistency sweep)

- **More removals offer Undo through the same helper:** the full player's Up next ✕, un-pinning and forgetting from
  Recently (the stores return a `PinSlot` so Undo restores the slot), forgetting a recent search (`HistorySlot`), and
  unticking a channel in a group's edit sheet (that sheet hosts its own snackbar, since a modal sheet covers the shell's).
- **The playing entry.** Removing it keeps the sound playing but leaves no row current (the queue's place becomes the entry
  above), so the next advance plays what followed it; Undo puts it back in its old slot as the current entry while it is
  still what plays. This is the coordinator's call; Dewi delegated it, 2026-11-06.
- **🙅 Not for me on what is playing skips to the next item** (a rejection should not keep playing; with nothing after it,
  playback pauses). One class, `NotForMeRejecter`, does the removal, the suppression, the skip and the Undo for an item and
  for a torrent pack (the pack is suppressed by its show title, its queued files are removed and restored). This is the
  coordinator's call; Dewi delegated it, 2026-11-06.
- **One window per delete batch.** `PendingDownloadDeletes` commits a batch's files together when its Undo offer has run
  its course, and a batch announced inside the previous window waits for it; before, each file went 12 s after its own
  delete and a long burst could commit its first files before the Undo appeared.

### Third pass, 2026-11-06 (decisions Dewi delegated to the coordinator)

Dewi said "your call on each"; each of these is the coordinator's call, and Dewi delegated it, 2026-11-06.

- **Deleting a whole playlist and clearing the play history offer ↩️ Undo, with no confirm dialog and no hold to
  confirm.** The same `rememberRemoveWithUndo` serves both: the stores hand back what they removed
  (`LocalPlaylistStore.delete` returns a `RemovedPlaylist` with its id, name, creation time and items;
  `PlayHistoryStore.clear` returns the entries, most recent first) and take it back (`restore`). A playlist returns under
  its own id, with every item in its order and in its old place in the list (Room orders by creation time, which the
  `RemovedPlaylist` carries); the history returns in its old order **behind** anything played during the undo window and
  never past its cap. The removed data is held in memory (`HeldRemoval`, awaited by Undo) for the snackbar's window
  (long), and Undo asks the application scope, so it still works after the screen that offered it has closed. Delete from
  the playlist's own page offers the same Undo, and the page closes as before. A playlist has no star or like side effects
  to put back: Starred is not a deletable playlist (`observeYourPlaylists` leaves it out). Limit: the snapshot is not
  persisted, so a process death inside the window loses the undo (and the data), as a confirmed delete would have.
  Neither screen is an exception to ADR 108; the confirm texts (`playlist_delete_confirm`, `history_clear_confirm`) are gone.
- **🙅 Not for me on the item playing now skips to the next item** (recorded above).
- **Removing the playing entry keeps it playing with no row marked as playing, and Undo puts it back as current** (recorded
  above and in `docs/features/playback-queue.md`).
- **What Not for me does not filter: the All subscriptions page.** Its latest-upload line is a fact about your own
  subscription (what that channel last published), not a suggestion, so a 🙅 item is still named there, like a channel or
  podcast page you opened yourself. The same line carries the live tag through the one rule of
  [ADR 132](0132-the-live-tag-is-read-from-one-registry-and-kept-true.md).

## Consequences

- A playlist Undo puts the item back in its old slot through `LocalPlaylistStore.insertItem`, so it matches the queue's.
- A whole-playlist Undo goes through `LocalPlaylistStore.restore`, and a history Undo through `PlayHistoryStore.restore`;
  both fakes and the Room stores follow one contract (`RoomUndoTest`, unrun, pins the Room side).
- Suppression is per item, not per channel: "never this channel" is a different feature.
- `enqueue` and `playNext` now return whether they added; callers that wanted the old silent move call `moveUp`.
