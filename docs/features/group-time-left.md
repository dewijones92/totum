---
title: Time left per queue group
kind: feature
status: built 2026-11-06, Batch A (tests written 2026-11-07)
area: queue
updated: 2026-11-07
---

# Time left per queue group

Dewi, 2026-11-06. [ADR 138](../adr/0138-a-queue-group-says-how-long-is-left-at-the-speed-it-will-play.md).

## What it does

Under each queue group's title: **⏳ 4 h 12 min left at 2x**. Without the speed when everything in the group plays at
1x ("⏳ 4 h 12 min left"); **⏳ At least 4 h 12 min left** when some lengths are unknown.

What counts: entries at or after the one playing (all of them when nothing plays) that the queue would not pass over,
so a finished episode does not count. Each is its length minus how far in you are, divided by its play speed: music
and live streams at 1x, everything else at the speed you chose. Shown on a group's header whether it is folded or not,
and not on an endless radio.

## Seams and reuse

- `timeLeft` (`:core:domain`), `groupTimesLeft` (`:core:data`), `rememberGroupTimesLeft` and `timeLeftWords`
  (`ui/queue/GroupTimeLeft.kt`); `GroupHeaderModel.timeLeft` carries it, via `QueueLayout.timesLeft`.
- `MediaContentKind.playsAtSpeed` is now the one "what speed does this kind play at" rule; `tuningFor` uses it.
- `PlaybackQueue.passOver` (the stepping rule) says what is passed over; `speedLabel` and `minutesLabel` word it.
- `PlaybackSpeedStore.chosen` (new) and `AppContainer.chosenSpeed` expose the one global speed.

## Diagnostics

`queue` line with `dewidebug`: the time left per group (id, duration, how many are of unknown length, whether the speed
was applied), the cursor's place and the chosen speed, logged when any of them changes.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`TimeLeftTest` (`:core:domain`, 13: unplayed, at 2x, part-way by the state's length and by the item's, played in full,
unknown lengths as "at least", live excluded, music never sped, a mix, an audiobook, rounding up to the minute, nothing
known, a position past the end), `PlaysAtSpeedTest`, `GroupTimesLeftTest` (`:core:data`, 9: per group, from the current
place, passed-over entries, unknown lengths, a split group), instrumented `BatchAScreensTest` (the four wordings) and
`BatchAStoresTest` (`SharedPrefsPlaybackSpeedStore.chosen` follows a save and the first read; restores the speed).
Seen failing with the minute rounding changed.

Still owed: instrumented, the real queue group header showing the line.
