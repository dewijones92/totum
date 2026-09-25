---
title: The hour-long live test stalls about nine seconds in, on CI only
kind: todo
status: open; intermittent (6 of the last 13 CI runs); cause not established; passes on totum-api35 (signed in)
area: playback
updated: 2026-09-25
---

# `AnHourLongItemDoesNotRebufferTest` stalls early, on CI

`anHourLongVideoPlaysOnWithoutRebuffering` is the most frequent red in the instrumented job. Counted
from the check-run annotations of the last 13 CI runs on `main` (2026-09-22 → 2026-09-25):

| Commit | Result | The stall |
|---|---|---|
| 1826895 (UI overhaul) | failed | `not advancing (wants to play)` at **17678ms**, one stall |
| 6566734 | passed | |
| 60cb863 | failed (a different test, FourK) | |
| 1fb080e, 30fb4a8, 3099076, 8706584 | passed | |
| becc4e0 | failed | via **hls**, `not advancing` at **9511ms** |
| cccf25e | failed | via a **direct url**, at **9506ms** and again at 19771ms |
| a32957a | failed | via **hls**, SABR fetch failed at **9510ms** |
| 387d8fc | failed | same test |
| 70ea678 | failed | via **sabr**, fetch failed at **9514ms** |

What the evidence does say:

- **It lands at ~9.5 s into the test in four of the five older failures**, across three different routes
  (hls, direct url, sabr). A stall that keeps the same clock time across unrelated routes points at
  something on a timer in the app or the test, not at the stream. That is a lead, not a cause.
- **It is not the UI.** It failed the same way on five commits that touched no UI, and the overhaul's
  own build passed it twice in a row on `totum-api35` (2026-09-25, 2/2 each run).
- **Local is not a control for CI**: the emulator is signed in and CI is signed out, so the two take
  different routes (see [sabr-cannot-seek.md](sabr-cannot-seek.md) on the ~62 s signed-out wall).

Next step, when picked up: log what fires at 9–10 s after play in the test's trail (timers, the queue's
auto-download check, the stall detector's own clock) and read one failing run's per-test logcat from
the `instrumented-reports` artifact, rather than theorising.

## Two more intermittent reds, on the rerun of 1826895 (2026-09-25)

The first attempt of run 36183070187 failed only this test. Re-running the failed job passed it and
failed two others instead, both of which had passed on the first attempt of the same commit:

- **`AutoAdvanceLoopTest` — "an item that finishes starts the next one"**: the trail shows the silent wav
  `first` reaching `playing at 0ms`, then `idle` at 434ms and `not advancing (wants to play)`, with the
  queue's automatic download pass running over both items at the same moment. Playback of a local file
  went idle without an error line. Not a UI path.
- **`MultiSelectTest` — "select all in the queue leaves a collapsed group alone"**: the group header
  tag was not in the tree right after `setContent` + `waitForIdle`. The CI emulator is **320x640dp at
  160dpi** (from the per-test logcat), and the test passed 3 of 3 on `totum-api35` forced to exactly that
  geometry (`wm size 320x640`, `wm density 160`), so it is not the screen size.

Neither had failed in the previous 12 runs. Recorded here so a third sighting is recognisable.
