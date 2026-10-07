---
title: continueTotumKeepsTheVideoPlayingDuringTheWalkingBreak times out on totum-api35
kind: todo
status: state-dependent; 18:4x recurrence was host load (a second emulator at ~10 cores); the podcast twin's CI failure was a test bug, fixed
area: testing
priority: low
requested: 2026-10-07
updated: 2026-10-07
---

# The Continue Totum video test times out on the emulator

`ExsurgeTakeoverFlowTest.continueTotumKeepsTheVideoPlayingDuringTheWalkingBreak` failed 3 of 3 on
`totum-api35` (swiftshader) on 2026-10-07: `ComposeTimeoutException` at line 138, waiting 10 s for the
local VIDEO fixture to report `isPlaying`, before Exsurge is involved. The podcast twin passes.

Control: the tree at d56ec3f (before the Live Update commit), built from `git archive`, failed it 2 of 2
the same way. So it is not that change. It passed on 2026-10-06 at 1080×2400 and 320×640 (feature doc),
so either the emulator state differs (it had been up 22 h, then cold-booted) or something since then.

Next: run it with the fixture's decoder logs (`format`/`video size` lines), and on a freshly wiped AVD,
before calling it environment. Related memory: position-based playback tests measure the decoder.

## Correction (later the same day)

The title overstated it. After the emulator was cold-booted and other runs had finished, the same test
passed in two full Exsurge runs (21 of 21, then 22 of 22, 17:0x–17:1x). The five failures came on an
emulator that had been up 22 hours and then had manual Exsurge runs and a banner service left running.
So: state-dependent, not broken. In the same session `pausedAndOutOfHoursKeepAQuietOngoingBanner` failed
once ("notification 7301 was never posted") and then passed alone, as a class and in the full run, which
points the same way: a banner service left running by earlier manual use changes the reconcile path.
If either recurs, record `ExsurgeBannerService.running` at the test's start.

## 18:4x: the cause this time was host load, and the podcast twin had a real test bug

It failed again at line 138 in 3 of 6 class runs, with load average 24 on the laptop: a second
emulator (`loquax-api35`) was using ~10 cores. The logcat shows the local file taking 3 s to reach
`transition` and then `gave up buffering after 7266ms at 10000ms`. So the decoder was starved, not
Exsurge. (Not re-run on an idle host this time; the 17:0x passes above were on a quieter one.)

Separately, CI failed `continueTotumResumesThePausedPodcastWithoutReplayingIt` twice at b85cfccb with
"the current item was restarted". That was not a restart. The test paused 26 ms after `isPlaying`, before
any audio had been rendered, so the reported position was ExoPlayer's clock extrapolation (10027 ms).
On resume it re-synced to the audio sink's real head (10000 ms), which is lower. The passing run at
c568b90e had paused at 10073 ms and resumed at 10082 ms. Fix: the test now waits until the position
reaches 10.3 s before it pauses, so `before` is a position that was really played.
