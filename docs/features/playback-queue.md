---
title: Playback queue
kind: feature
area: playback
status: shipped
updated: 2026-10-15
---

# Playback queue

One up-next list for both pillars (`app/…/queue/PlaybackQueue.kt`), persisted and restored at
launch. This doc started on 2026-09-26 with the row actions; the rest of the queue's behaviour is
still described in the code and in `offline-queue.md`.

## Gapless hand-over

Since 2026-10-07 ([ADR 20](../adr/0020-gapless-queue-the-player-holds-the-next-item.md)): in an item's last
45 s, on Wi-Fi or when the next item is downloaded, the next item is routed as usual and put in line in the
player, which crosses over by itself with no rebuild; the queue then adopts it. Settings › Queue ›
**Gapless queue** (on by default) turns it off. Not armed with auto-play next off or the sleep timer set to
stop after this item. Every decision is a `dewidebug [gapless]` line.

## What a queue row can do

Tap a row to jump to it: the cursor moves there and nothing is reordered. The row's ⋮ sheet adds:

| Action | What happens |
|---|---|
| **Play now, then back to what's playing** | The row takes the now-playing slot and starts; what was playing drops one place and is up next, resuming where it was. Offered on every row except the playing one, when something is playing or marked as playing |
| Play next | Moves the row to just after the playing item |
| Remove from queue | Removes it, with Undo in a snackbar |
| Move to top / Move to bottom | Reorders it |

**Dragging** (ADR 40): the ≡ grip drags a row; it follows the finger by the list's real positions, the other
rows slide aside, and holding it at an edge scrolls. A group header's ≡ drags the **whole group** (it folds
first). While any drag is in flight a split group's second header is hidden, so the layout cannot shift under
the finger.

**Music ⇄ talk** (ADR 42): 🎵 / 🎙️ in the mini player, the full player's Up next, the Queue header and the
media notification jumps to the other kind within the queue (the one left last, else the nearest); with no
music queued it plays the newest music from Recently. `dewidebug [switch]` lines say what it chose and why.

**Groups** (ADR 40; Dewi, 2026-10-14: *"i want to be able to shuffle them and move them (just like any other
queue item)"*): a group header's ⋮ has Play next, Move to top, Move to bottom, Shuffle this group and Remove
these. A group moves as one block, gathered in order even if it had been split. The Queue header's 🔀 asks
**Shuffle up next** or **Shuffle the order of groups** when up next has a group (each group stays together and
in order). Neither touches what has played or what is playing.

A group keeps what has played (Dewi, 2026-10-14: *"I don't want the group to shrink if I've played
things"*): playing through it and tapping a song already in it both leave the song in its group.
`QueueGroupPlaybackTest`.

A group header's 🔀 shuffles the whole group and plays it (ADR 40, amended 2026-10-14); never the same song
again when the group is already playing.

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

## Titles after an offline share

A shared link queued under its placeholder title learns its metadata in the background once the
network returns, even when its audio was downloaded and playback takes the local-file route. The
same repair reaches downloads kept only in Library. See [shared-link metadata](shared-link-metadata.md).

## Changing the playing item's mode

The player Watch/Listen buttons and a mode action on the playing row reroute the actual playing
item through the shared queue, retaining its current position and queue slot. A stale item id is
ignored. A mode change disarms the prepared successor so it can be routed for the new mode.
Shorts uses this same route to request the picture for the playing short without changing the
saved playback mode. It requests the picture once per playing item, so recovery can keep audio
playing after a failed picture without the reel requesting it again. A full downloaded video stays on disk in Listen mode, with its video track disabled; switching
back to Watch restores that track. An audio-only copy can switch to its stream for Watch.
Every local-file play clears the launcher's previous stream choices and forwards its resume point.
The file route carries an explicit audio-only flag. A podcast enclosure can contain video, so its
picture is retained unless Listen or an audio-only copy requires sound alone. Immediate playback
and gapless preparation apply the same flag.

Sponsor refresh returns the latest segment list even when a concurrent download refresh has
already stored it. The player keeps updates arriving before its asynchronous resume lookup finishes.

These repairs ship in [v0.1.603](https://github.com/dewijones92/totum/releases/tag/v0.1.603),
verified on 2026-10-12 with failing-before/passing-after regressions, real-player and Shorts cases,
and the paired playback/queue comparison. Release attempt `v0.1.602` remains withdrawn from stable
updates; 603 includes the podcast-picture correction and passes the full local gate, affected
device cases and fresh paired soak criteria. Its downloaded APK version, signature and release
commit were verified.
CI's build and offline device suites passed. The separate live-streaming suite remains red on
a repeated long-video stall and a SABR failure also present in 601; those streaming issues remain open.
See [current phone findings](../todos/playback-defects-0.1.601.md).

## Jump to what's playing, and collapse all (2026-10-15)

Dewi asked for a button to scroll the queue to the playing item, and one to collapse every group. The header has
⌖ (jump), which also runs each time the Queue tab opens, and a collapse-all / expand-all toggle shown when the queue
has groups. Jumping expands the playing item's group if it is collapsed, scrolls to its estimated position
(`estimatedListIndex`: rows and group headers above it, collapsed groups counted as one header), then settles on
the row by its key. The collapsed set now lives above the header, so the toggle and the list share it.
Logged: `jumped to the playing item (…): estimate N, found at M`. Tests: `EstimatedListIndexTest`. Verified on
`totum-api35` with a 12-item queue on item 10: the tab opened on "Now playing".

## A tap that cannot play says so (2026-10-15)

A queue tap, play now or play all that cannot start shows a toast: ❌ Couldn't play "X": not downloaded and no
network / its stream will not play / YouTube gave no stream (signed out: "signing in usually fixes this"). An
automatic advance or a gapless adoption stays quiet. `PlaybackQueue.refusedTaps`, shown by `RefusedTapToasts`.
Test: `ARefusedTapSaysSoTest`.

## ⏳ Starting… (2026-10-15)

From the tap until the play lands or fails, the queue publishes what is starting (`PlaybackQueue.starting`,
`PlayAttempts`): the mini player shows the tapped title with "⏳ Starting…" and a spinner, and its queue row says
the same. The log says how long it took (`"X" started 2140ms after the tap`). Report 0.1.625 showed a song tapped
about 40 times while it resolved, because nothing on screen changed. Only a tap shows it (automatic plays are quiet), and any other item becoming current clears it; with nothing played yet, the dock shows a starting bar instead. Tests: `PlayAttemptsTest`, `ARefusedTapSaysSoTest`, `DockStartingTest` (device).
