---
title: A stall rescue in flight overrides a pause
kind: todo
status: fixed 2026-10-02 — the 2026-10-01 fix did not work on real Media3; the hold replaces it
area: playback
priority: medium
requested: 2026-10-01
updated: 2026-10-02
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

## First fix (2026-10-01), which did not work

**This section claimed a fix that was not one.** The third review of Exsurge (2026-10-01) found it.

`replayCurrent` watches the state while it waits. If the item went from wanting to play to paused
during the rescue, the replay is followed by `setPlaying(false)` and a log line. Only a true→false
transition counts, so a recovery that starts from an error state behaves as before.
`APauseDuringARescueSurvivesItTest` was seen red, then green, with a control that a rescue nobody
paused still plays. The existing stall-recovery instrumented test (2) passes on `totum-api35`.

That `setPlaying(false)` ran straight after `play()`. On the fake, `play()` is synchronous, so the test
passed. On the real `Media3PlaybackController`, `play()` launches a coroutine that calls
`controller.play()` later, so the pause came first and then the player started anyway. The JVM test
was green and the bug was still live.

## Fixed (2026-10-02)

The intent now goes into the play call itself. `PlaybackController.holdPausedForNextPlay(itemId)`
marks an item, and the next `play()` of that item calls `prepare()` and then `pause()`, never
`play()`.

Every rescue goes through one guard, `RescueIntent.keeping` in `app/…/queue/`. That covers the
replay and all three ladder rungs: from the disk, over SABR, and the sound without the picture. For
as long as a rescue runs, the guard holds the item paused whenever its state says it does not want
to play, and releases the hold when it does.

The guard reads the state **synchronously when it starts**, and then follows every change.
- **A pause made before the rescue began is kept.** `StreamRecovery` can wait minutes for a network
  before calling `replay`, and a pause made in that wait counts.
- **The hold is put back after each held play,** so the next rung down the ladder starts paused too.

A fresh play of the same item (a tap, not a retry) drops the hold until the player reports that it
wants to play, so tapping a paused item during its rescue plays it. After that the rescue keeps a
later pause again.

**A behaviour change worth knowing:** a rescue now keeps whatever intent it finds. An item that was
prepared but paused when its stream failed is replayed paused, where it used to be replayed playing.
That covers a restored queue at cold start, a Cast hand-back, and the app's own pauses (headphones
out, sleep timer, Exsurge). The log says "is paused during its replay".

Concurrent rescues of one item are reference-counted, so the first to finish cannot release
another one's hold.

A second Opus review found the gaps in the first version of this fix (2026-10-02). That version only
watched for a pause *during* `replayCurrent`, so a pause before the rescue began was undone, and the
other rungs had no hold at all.

Tests:
- `APauseDuringARescueSurvivesItTest` (8). Five of them were seen red against HEAD, and the re-tap test was seen red against the first version of the guard. One of those, the
  concurrent-rescue test, was also seen red with the reference count disabled.
- `AHeldPauseStartsPausedTest` (instrumented) drives the **real** controller on `totum-api35`. It
  clears the hold straight after `play()`, as the guard does, so it fails if the hold is ever read
  late.
- The existing stall-recovery tests (2) still pass.

Not covered: a pause in the millisecond window after Media3's `play()` has returned but before its
launched coroutine starts the player. That race predates this fix and applies to every `play()`.
