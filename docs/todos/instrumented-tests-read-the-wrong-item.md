---
title: Instrumented tests read the player without asking which item it is on
kind: todo
status: cause fixed and sixteen tests scoped; each still needs its live run to confirm
area: testing
updated: 2026-09-25
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
- 2026-09-25: converted every wait and every `controller.state.value` read feeding an assertion in
  `LiveSabrDownloadTest`, `LiveDownloadedVideoOfflineTest`, `MeteredAudioSwitchDeviceTest`,
  `FourKActuallyPlaysTest`, `StreamPlaysToItsEndTest`, `LiveStreamPlaysToItsEndTest`,
  `SilenceStrategyDeviceTest`, `AnHourLongItemDoesNotRebufferTest`, `PlaysAcrossContentTypesTest`,
  `SubtitlesArriveAndRenderTest`, and `TorrentQueuePlaybackTest`, plus the unscoped part of
  `AutoAdvanceLoopTest`, `StalledStreamRecoveryTest` and `OfflineQueuePlaybackTest` (each already
  had some correctly itemId-scoped waits, left alone). Where a read could not be scoped via
  `awaitStateOf` (a `player.videoSize` read, and a couple of failure messages), it now names the
  impostor with `whatIsActuallyPlaying()` instead of reading the ambient state directly.
  `./gradlew detekt assembleDebugAndroidTest` and `tools/ci/preflight.py` are green, but none of
  this has been run on a device or emulator — most of these are live YouTube tests this laptop
  cannot execute, so each conversion still needs its live run to confirm the scoping is actually
  correct and not just compiling.

## Still open

Every listed test above is converted, but **none has been verified live** — that is the next
step, via `tools/ci/live-test-via-home.sh` where the test needs it, or a local emulator run for
the deterministic ones (`StreamPlaysToItsEndTest`, `SilenceStrategyDeviceTest`,
`AutoAdvanceLoopTest`, `StalledStreamRecoveryTest`, `OfflineQueuePlaybackTest`,
`TorrentQueuePlaybackTest`, `MeteredAudioSwitchDeviceTest`).

## The general rule

A wait must name the thing it is waiting for. "Something is playing" is not the question any of
these tests is asking, and it is the question all of them were putting.
