---
title: Time left per queue group
kind: feature
status: built 2026-11-06, Batch A (tests owed)
area: queue
updated: 2026-11-06
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

## Tests owed

- `TimeLeftTest` in `:core:domain`: unplayed, in progress (state's duration, item's duration), played counted in full,
  unknown and live giving "at least", all unknown giving null, music at 1x, speed named only when something is sped,
  rounding up to the minute.
- `GroupTimesLeftTest` in `:core:data`: only entries at or after `from`, `countsAsLeft` honoured, ungrouped entries
  skipped, a split group totalled together.
- `PlaysAtSpeedTest` (every kind) and the existing `tuningFor` tests as the guard that playback did not change.
- `SharedPrefsPlaybackSpeedStore.chosen` follows `save` and the first `speed()` (androidTest).
- Instrumented: a group header shows the line, "at least" and the speed, and none on an endless radio.
