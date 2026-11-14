---
title: ExsurgeTakeoverFlowTest's paused-podcast case fails now and then on CI
kind: todo
status: fixed 2026-10-29 (82b7fc38); 3 of 3 on totum-api35 2026-11-14; the enabled-left-on question is open
area: exsurge
priority: medium
requested: 2026-10-23
updated: 2026-11-14
---

# ExsurgeTakeoverFlowTest's paused-podcast case fails now and then on CI

`continueTotumResumesThePausedPodcastWithoutReplayingIt` failed with "the current item was restarted"
(`ExsurgeTakeoverFlowTest.kt:169`: the position after Continue was below the one read at the pause) on
`0fb35de5` (2026-10-22) and `1a2d2c35` (2026-10-23), and passed on the ten Exsurge runs between. Neither
change touched playback. It passed 3/3 on totum-api35 against the 1a2d2c35 test APK.

To find out: log the position the test reads at the pause and the one after Continue (and the state's
`updatedAt`), so the next red run says whether the player really went back or the test read a stale state.
A flaky test is a defect either way; do not re-run CI to make it green.

Also: running `ExsurgeTakeoverFlowTest` leaves Exsurge **enabled** on the device (2026-10-23, totum-api35:
`enabled:true`, state `sitting`, summoning "Call 2 of 3" after a reboot). It dispatches `TurnOff` at the end
but never restores the `enabled` setting it found, so the emulator starts summoning and spoils the next
soak.

Correction, same day: the test DOES restore the settings it captured (`restore()` sets `before`), and a
single rerun on totum-api35 left `enabled:false` on disk, as it should. So the cause was not shown.
Candidates, both unproven: the restore is written with `apply()` (asynchronous) and instrumentation
can kill the process before it reaches disk; or the emulator, OOM-killed at 16:32 that day, rebooted
from an older snapshot. If it recurs, read `shared_prefs/exsurge.xml` straight after a run and note
whether the emulator restarted in between.

The CI flake's assertion now carries both positions and the playing state, and logs them under
`dewidebug continueCurrent`, so the next red run says whether the player went back or the test read a
stale state.

## Cause and fix (found 2026-11-14 from the history; the todo had not been updated)

The cause was the assertion, not the player. `assertNotRestarted` required `after.positionMs >= position`
with nothing to spare, and Media3 reports the position around a resume a few milliseconds back: the
2026-10-29 red run on `2f985aef` read `10605ms before Continue, 10601ms after`. A real restart lands near
0 ms. `82b7fc38` (2026-10-29) changed the check to `after >= before - RESUME_JITTER_MS` (1 s), re-run twice
on totum-api35 at 8 of 8. The two CI reds recorded above (`0fb35de5`, `1a2d2c35`) are the same shape: both
were on changes that did not touch playback, and both are before the fix.

Re-checked 2026-11-14 on totum-api35 against the debug and test APKs built from `bf7d878b` plus two playback
fixes: `am instrument -e class ...ExsurgeTakeoverFlowTest#continueTotumResumesThePausedPodcastWithoutReplayingIt`
printed `OK (1 test)` in 12.7, 13.0 and 14.4 s on three runs in a row. A fourth run was cut short when the
emulator process died with a segfault (signal 11, qemu-system-x86_64-headless, 06:55 that morning; not shown to
be caused by the test), so no recurrence evidence beyond three passes, and the `dewidebug continueCurrent`
lines of those runs were lost with the device.

Still open: whether a run leaves Exsurge `enabled` on the device. `shared_prefs/exsurge.xml` could not be
read after these runs because the emulator died first.
