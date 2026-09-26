---
title: Playback queue
kind: feature
area: playback
status: shipped
updated: 2026-09-26
---

# Playback queue

One up-next list for both pillars (`app/…/queue/PlaybackQueue.kt`), persisted and restored at
launch. This doc started on 2026-09-26 with the row actions; the rest of the queue's behaviour is
still described in the code and in `offline-queue.md`.

## What a queue row can do

Tap a row to jump to it: the cursor moves there and nothing is reordered. The row's ⋮ sheet adds:

| Action | What happens |
|---|---|
| **Play now, then back to what's playing** | The row takes the now-playing slot and starts; what was playing drops one place and is up next, resuming where it was. Offered on every row except the playing one, when something is playing or marked as playing |
| Play next | Moves the row to just after the playing item |
| Remove from queue | Removes it, with Undo in a snackbar |
| Move to top / Move to bottom | Reorders it |

**Play now, then back to what's playing** (Dewi, 2026-09-26: *"an option on a queue item to move it
in to the position of the current in play one? And the current in play one just moves down one
position"*):

- Acts on the entry the row shows, matched **by item id** (as removal and de-duplication are), never
  by an index read at composition. That index is stale the moment anything above it moves, which is
  how one swipe once removed several rows.
- "What is playing" is the item the player has, or, only when the player has nothing loaded
  (straight after a restart), the entry the queue shows as **Now playing**. The screen and the action
  agree on which one moves down; the first on-device try found that they did not. When the playing
  item has been taken out of the queue, nothing already heard is promoted: the chosen entry plays
  where it is.
- **If the chosen entry will not play** (offline with no copy, a failed resolve), the queue is put
  back as it was, with what is still playing as current, so it does not replay when it ends.
- An entry that has already left the queue plays nothing. On the playing entry it changes nothing.
- Every use logs `[queue] play-instead: …` with the slot the entry took, and whether the one moved
  down was playing or only marked as playing.

Tests: `PlayInsteadOfCurrentTest` (9): after the current, before it, after a restart with nothing
loaded, nothing marked at all, the playing entry itself, an entry that has left, what was playing
coming back when the chosen entry ends, a chosen entry that will not play, and the playing row
having been removed. Two failed against what a tap does today (jump to it); the restart case
failed against the first version; the refusal and removed-row cases failed until the review fixes.
Checked on the emulator twice: two rows swapped, and in a queue of three the chosen one took the
playing slot with the playing one next.
