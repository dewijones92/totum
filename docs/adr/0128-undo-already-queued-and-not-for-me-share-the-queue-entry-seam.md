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
  search, a playlist) still show everything. The menu action
  removes the item from the queue and records it, with an Undo that does both back. Settings → 🙅 Not for me lists and
  allows items again.

## Consequences

- A playlist Undo puts the item back in its old slot through `LocalPlaylistStore.insertItem`, so it matches the queue's.
- Suppression is per item, not per channel: "never this channel" is a different feature.
- `enqueue` and `playNext` now return whether they added; callers that wanted the old silent move call `moveUp`.
