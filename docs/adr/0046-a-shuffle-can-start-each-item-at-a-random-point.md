---
title: A shuffle can start each item at a random point
kind: adr
status: accepted
updated: 2026-10-15
---

# 46. A shuffle can start each item at a random point

- Status: Accepted
- Date: 2026-10-14

## Context

Dewi, 2026-10-14: *"when I shuffle anything, I want to be given a prompt … shuffle the positions … it will play
some items from halfway through"*. He chose "anywhere": from the start to just before the end.

## Decision

- Every shuffle asks first (`rememberShuffleAsk`): a 🔀 dialog with **🎲 Start each at a random point**; a channel's
  Include Shorts question carries the same checkbox. The last answer is remembered for the session.
- Ticked, the shuffled items get a start picked from 0 to the length minus min(10 s, a tenth), held by
  `RandomStarts`, which wraps the one resume store (`PlaybackProgressStore`) for both pillars. Reading it does not
  use it up (arming the next item for gapless reads it too); the item's first saved position does, and a start
  is dropped when its item leaves the queue (both found by the review). Saved in `files/random-starts.txt`. Items with no
  known length start as usual, and the log says how many.
- Covered: Shuffle all (channel, podcast, group chip), the queue's up next, group order, a group, shuffle-and-play,
  an artist's songs, All songs, an album. Pinned shortcuts replay without asking.
- The resolver peeks at it too, so a random start takes the extraction route rather than SABR, which cannot seek.

## Update, 2026-10-15: unticked means from the top

Dewi: *"if I don't select [random], I want to reset the position of each item back to zero"*. Unticked, every
shuffled item now starts at 0:00 once (`RandomStarts.assign(…, atRandom = false)` holds a start of 0), then
resumes as usual from its next saved position; a later shuffle from the top replaces an earlier random start.
The saved place is not deleted, so a mid-listen item is only restarted when it is played from the shuffle.

## Consequences

- Tests: `RandomStartsTest`, `ShuffleAllActionTest` (device).
