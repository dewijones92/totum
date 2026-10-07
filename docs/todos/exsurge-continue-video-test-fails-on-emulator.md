---
title: continueTotumKeepsTheVideoPlayingDuringTheWalkingBreak times out on totum-api35
kind: todo
status: open — seen 2026-10-07, pre-existing (fails the same on the build before the Live Update change)
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
