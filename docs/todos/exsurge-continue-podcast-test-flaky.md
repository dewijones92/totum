---
title: ExsurgeTakeoverFlowTest's paused-podcast case fails now and then on CI
kind: todo
status: open
area: exsurge
priority: medium
requested: 2026-10-23
updated: 2026-10-23
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
