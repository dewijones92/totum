---
title: The hour-long live test stalls about nine seconds in, on CI only
kind: todo
status: open; since 2026-10-08 it fails on EVERY CI run that reaches it, at media position 19.41 s (see the 2026-10-14 section); cause not established; passes on totum-api35 (signed in)
area: playback
priority: high — it is the standing reason CI is red on main
updated: 2026-10-14
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

## 2026-10-14: no longer intermittent, and why the picture changed

Re-read from the check-run annotations of every red `main` run since 2026-10-08, and the per-test logcat
of three of them (runs 38429636795, 38292877558, 37815227465, from the `instrumented-reports` artifact):

| Run (commit) | Date | Stall | Lasted | Bandwidth at the stall | Oldest load in flight |
|---|---|---|---|---|---|
| 37815227465 (4cd4a804) | 10-08 | 19,408 ms | 50 ms | ~1,777 kbps | 21.4 s |
| 37954125510, 37957711766 | 10-09 | 19,413 / 19,408 ms | | | |
| 38256355632, 38276659244 | 10-12 | 19,407 / 19,413 ms | | | |
| 38292877558 (a57f66db) | 10-12 | 19,407 ms | 2 ms | ~1,706 kbps | 21.6 s |
| 38429636795 (f8214859) | 10-13 | 19,415 ms | 39 ms | ~13,553 kbps | 20.0 s |

- **It fails every time now (7 of the last 8 red runs; the eighth failed elsewhere).** The earlier
  "intermittent" reading was right for September but is stale: on 2026-10-08 `4cd4a804` fixed a stale
  home-IP secret that had been making the whole live phase **skip while CI stayed green**. Since the live
  tests run again, this one has failed on every run. So it is not a regression from that week's features;
  it was hidden.
- **Same in every run:** the video `uSMGENDH_QI`, a **direct url** route (not SABR, not HLS) from a lookup
  **cached 8–9 minutes earlier** by a test that ran before it, and the stall at **media position
  19.41 s ± 4 ms**. The test's other variant (`anHourLongItemPlaysOnAsAudioWithoutRebuffering`) passes.
- **The stall is a blip** (2–50 ms), then play resumes and the buffer ends 225–280 s ahead. The test
  fails on any rebuffer, by design ("the spinner coming back is what makes it unusable").
- **Bandwidth falls from ~60 Mbps at the start to ~1.7 Mbps at the stall**, with three requests that were
  opened at play start (20–21.6 s earlier) still in flight.

A fixed media position, not a fixed clock time, across runs with very different chunk sizes (334 KB, 400 KB,
932 KB) says the first burst of data runs out at the same point in the file every time.

### Hypotheses, and the experiment that separates them

1. **YouTube serves a fast first burst on a reused URL and then throttles it to about real time.** An
   8-minute-old URL behaves differently from a fresh one. *Test:* clear the resolve cache before this
   test (or resolve the video with a different id first), so it plays a fresh URL; if the stall goes,
   this is it.
2. **The home tunnel throttles after an initial burst**, not YouTube. *Test:* on the CI runner, time a
   plain `curl` of the same `videoplayback` URL with a 0–30 MB range through the tunnel, logging bytes
   per second.
3. **The player's buffer targets leave too little ahead once the burst ends**, so a real-time trickle
   drains it at a deterministic position. *Test:* log the load control's buffered-ahead target and the
   bytes per request every second from 15 s to 22 s (the `[playback] in flight` line already has the
   requests; add bytes so far and the time of each request's last byte).
4. **Locally the route is different, so local passes prove nothing about this.** The emulator is signed
   in and takes another route. Comparing like with like needs a signed-out device: a second AVD
   (`totum-api35-signedout`), never signing the main one out.

### Decision Dewi may need to make

If it is (1) or (2) and the real phone never sees it, the question is whether a 2–50 ms blip counts as a
failure. Do not quietly loosen the assertion: the test exists because rebuffering made long items
unusable. Settle the cause first, then decide.
