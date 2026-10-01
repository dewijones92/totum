---
title: A stall rescue in flight overrides a pause
kind: todo
status: fixed 2026-10-01 — a pause made during a rescue now survives it
area: playback
priority: medium
requested: 2026-10-01
updated: 2026-10-01
---

# A stall rescue in flight overrides a pause

**Found while verifying Exsurge et Disce on `totum-api35`, 2026-10-01.** This is not Exsurge's bug.
Exsurge's break was just the pause that ran into it, and it would equally override a pause tapped
by hand.

## What happened (logcat, build 543150c + fixes)

| Time | Line |
|---|---|
| 13:31:47.023 | `[advance] CM2oM6Gm3gk stalled 20000ms at 0ms — STUCK … replaying it from there with a fresh stream (rescue 1 of 2)` |
| 13:32:10.665 | `interrupt: paused item=CM2oM6Gm3gk at 0ms` (the Exsurge break pauses it) |
| 13:32:13.246 | `[engine] extract … in 26215ms` (the rescue's re-resolve finishes) |
| 13:32:13.742 | `[playback] play CM2oM6Gm3gk from …`, then `[advance] CM2oM6Gm3gk stall replay=true` |
| 13:32:14.022 | `interrupt: no longer holding item=CM2oM6Gm3gk (playback was resumed …)` |

## Cause

`StallWatchdog.rescueOrGiveUp` (`app/…/playback/StallWatchdog.kt:256`) awaits
`replay(positionMs)`, which is `PlaybackQueue.replayCurrent` (wired at `AppContainer.kt:845`). That
suspends for the whole re-resolution (26 s here) and then calls `play()`, which always starts
playback. A pause made during that window is silently undone. `StreamRecovery` passes the same
`replay` (`AppContainer.kt:882`), so an expired-URL recovery probably has the same race. That is
unverified.

## Fix (proposed, needs Dewi's OK because it changes shipped recovery behaviour)

The replay should carry the user's *intent* across the wait: note `wantsToPlay` when the rescue
starts, and if it has become false by the time the fresh stream is ready, prepare it paused rather
than play it. A regression test on the fake: start a rescue, pause during the resolve, and assert
that the replayed item is not playing.

**Done when:** a pause made during a rescue survives it, on the JVM test and on the emulator.

## Fixed (2026-10-01)

`replayCurrent` watches the state while it waits. If the item went from wanting to play to paused
during the rescue, the replay is followed by `setPlaying(false)` and a log line. Only a true→false
transition counts, so a recovery that starts from an error state behaves as before.
`APauseDuringARescueSurvivesItTest` was seen red, then green, with a control that a rescue nobody
paused still plays. The existing stall-recovery instrumented test (2) passes on `totum-api35`.
