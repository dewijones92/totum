---
title: A locked download is kept until it is unlocked
kind: adr
status: accepted
updated: 2026-10-22
---

# 75. A locked download is kept until it is unlocked

- Status: Accepted
- Date: 2026-10-22

## Context

Dewi, 2026-10-22: a download such as Miss Rachel should not be easy to delete by accident: never suggested for
clean-up or removed automatically, and deleting it should ask to unlock first. Deletes reach the download manager from
six places (row menus, Library, podcasts, history, playlists, clean-up cards).

## Decision

- One decorator, `LockingDownloadManager` in `:core:data`, wraps the manager every screen already uses. Its `delete`
  keeps a locked item, logs it and announces the refusal; nothing else changes. So every delete path is covered by one
  rule, including ones added later.
- One question in the shell (`LockedDownloadAsk`) answers every refusal: "🔓 Unlock and delete" or Keep, counted when
  a bulk delete met several.
- Lock and unlock sit in every item's sheet beside "Delete download"; a locked row on the Downloads page says
  "🔒 Locked". Clean-up suggestions leave locked items out, and say how many in their log line.
- Locks are a string set in their own preferences file (`PrefsDownloadLocks`), not a Room column: no schema bump for a
  flag on a few items. Not in the Drive backup yet.

## Consequences

- A locked item can still be cancelled while downloading and re-downloaded; the lock is about deleting a finished copy.
- `LockingDownloadManagerTest`.

### Amendment (2026-10-22, after review)

A new download of a locked, finished item would have replaced its file (an audio copy upgraded to video shares one
path, and a cancel or failure lost the copy). The decorator now refuses that too, through both `download` entry points
(the interface's `download(MediaItem)` default would otherwise go straight to the inner manager), and the same shell
question offers "🔓 Unlock and go ahead". Only a finished copy is guarded: a failed row of a locked item can be
dismissed. `LockingDownloadManagerTest`, seen red.
