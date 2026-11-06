---
title: Time left per queue group
kind: todo
status: built 2026-11-06, Batch A (tests owed)
batch: A (2026-11)
priority: low
updated: 2026-11-06
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

## Tests owed

`TimeLeftTest` and `PlaysAtSpeedTest` (domain), `GroupTimesLeftTest` (data), `chosen` on the speed store, and an
instrumented header check.
