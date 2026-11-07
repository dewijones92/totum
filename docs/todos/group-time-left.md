---
title: Time left per queue group
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07)
batch: A (2026-11)
priority: low
updated: 2026-11-07
---

# Time left per queue group

Dewi 2026-11-06: each group header says e.g. "4 h 12 m left at 2×" (`GroupHeaderModel`); unknown durations make it
read "at least".

## Built 2026-11-06

Each queue group header says "⏳ 4 h 12 min left at 2x", or "at least" when a length is unknown, counting only what the
queue would still play. Detail: [group-time-left](../features/group-time-left.md),
[ADR 138](../adr/0138-a-queue-group-says-how-long-is-left-at-the-speed-it-will-play.md).

My calls: the speed is left out at 1x or in a group of music (it would say nothing); it is worded "2x" with the player's
`speedLabel`, and hours as "4 h 12 min", to match the wording already in the app; "left" excludes what the queue passes
over (finished talk), by `PlaybackQueue.passOver`; endless radio groups say nothing.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`TimeLeftTest` (`:core:domain`, 13: unplayed, at 2x, part-way by the state's length and by the item's, played in full,
unknown lengths as "at least", live excluded, music never sped, a mix, an audiobook, rounding up to the minute, nothing
known, a position past the end), `PlaysAtSpeedTest`, `GroupTimesLeftTest` (`:core:data`, 9: per group, from the current
place, passed-over entries, unknown lengths, a split group), instrumented `BatchAScreensTest` (the four wordings) and
`BatchAStoresTest` (`SharedPrefsPlaybackSpeedStore.chosen` follows a save and the first read; restores the speed).
Seen failing with the minute rounding changed.

Still owed: instrumented, the real queue group header showing the line.
