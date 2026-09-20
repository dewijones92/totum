---
title: Instrumented tests read the player without asking which item it is on
kind: todo
status: cause fixed and two tests scoped; fourteen more still read unscoped
area: testing
updated: 2026-09-20
---

# A green wait that was answered by the wrong video

`controller.state` describes whatever the player currently holds. `queue.playNow` returns as soon
as the queue row exists, and resolving a YouTube URL on a cold CI emulator took **27 seconds** on
2026-09-20 — for all of which the previous item was still playing. So a test that waits for
`isPlaying == true` is answered instantly by something else, and every figure it reads a line
later belongs to that something else.

## What it cost

`SeekDeepIntoALongVideoTest` failed with:

> the fixture should be long enough for an hour-deep seek to be nowhere near its end, but it
> reported 1330245ms

1,330,245ms is 22:10. NASA's "Cosmic Dawn" is 96:45 (confirmed against YouTube). 22:10 is the
length of a video called *School Stories That Sound Fake But Actually Happened* — which the app
had started **by itself**, 1.2 seconds after `LiveStreamPlaysToItsEndTest` finished, because that
test drives an item to its end and the end of an item with an empty queue is what makes autoplay
go looking for something related. It was streaming and downloading it throughout.

The assertion was true. The diagnosis it invited — "the fixture changed" — was wrong, and would
have been acted on. That is the cost of a failure that does not name what it actually saw.

## Fixed

- `PlaybackWaits` (androidTest/support) — waits scoped to a `MediaItemId`, plus
  `whatIsActuallyPlaying()` so a leak fails as "it is playing something else".
- `SeekDeepIntoALongVideoTest` now uses it for all four waits.
- `LiveStreamPlaysToItsEndTest` and `StreamPlaysToItsEndTest` turn `autoPlayNext` off for their
  duration, which removes the cause. Teardown cannot: the end has already happened by then.

## Still open

Fourteen instrumented tests still read `controller.state.value` without checking `itemId`:

`LiveSabrDownloadTest`, `LiveDownloadedVideoOfflineTest`, `MeteredAudioSwitchDeviceTest`,
`FourKActuallyPlaysTest`, `StreamPlaysToItsEndTest`, `LiveStreamPlaysToItsEndTest`,
`SilenceStrategyDeviceTest`, `AnHourLongItemDoesNotRebufferTest`, `PlaysAcrossContentTypesTest`,
`SubtitlesArriveAndRenderTest`, `TorrentQueuePlaybackTest`, and partially
`AutoAdvanceLoopTest`, `StalledStreamRecoveryTest`, `OfflineQueuePlaybackTest`.

Each is a latent version of the same failure. They are not converted in one pass because they are
live tests that cannot be verified from this laptop, and a wrong conversion is a red build with no
local way to tell it from a real one. Convert them as each is next touched, using `PlaybackWaits`.

## The general rule

A wait must name the thing it is waiting for. "Something is playing" is not the question any of
these tests is asking, and it is the question all of them were putting.
