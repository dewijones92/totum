---
title: A shuffle can start each item at a random point
kind: adr
status: accepted
updated: 2026-10-14
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
  `RandomStarts`, which wraps the one resume store (`PlaybackProgressStore`) for both pillars: the first
  resume of each item takes it once, then it resumes as usual. Saved in `files/random-starts.txt`. Items with no
  known length start as usual, and the log says how many.
- Covered: Shuffle all (channel, podcast, group chip), the queue's up next, group order, a group, shuffle-and-play,
  an artist's songs, All songs, an album. Pinned shortcuts replay without asking.
- The resolver peeks at it too, so a random start takes the extraction route rather than SABR, which cannot seek.

## Consequences

- Tests: `RandomStartsTest`, `ShuffleAllActionTest` (device).
