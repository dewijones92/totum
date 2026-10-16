---
title: The UI does no work per playback tick
kind: adr
status: accepted
updated: 2026-10-15
---

# 61. The UI does no work per playback tick

- Status: Accepted
- Date: 2026-10-15

## Context

Dewi reported Peep Show videos stuttering while watching, fullscreen or not, on both the Pixel 4a and the Pixel 7. A
perfetto trace on the 4a showed 43 frames of 130–150 ms, one every ~500 ms, while the Queue tab sat under the player,
and none with Library underneath. The 500 ms was the playback controller's position tick. Causes found:

1. The Queue screen and the app shell collected the whole `PlaybackState`, so each tick recomposed them, and the shell
   handed fresh lambdas to static composition locals, which recomposes everything under them.
2. `LocalPlayStates` and `LocalDownloadStates` were static locals carrying maps: every progress save (5 s) and every
   download progress write (once a second per download, four at a time) recomposed the whole app.
3. The full player recomposed whole on every tick.
4. Video drew into a `TextureView`, so every video frame forced the app's window to redraw.
5. The playing equaliser ran an infinite transition at 60 fps, paused or covered by the player.
6. The busy bar's indeterminate animation ran over the full player whenever anything loaded, a full-screen redraw at
   frame rate on top of the video.
7. The Queue screen called `observeDownloads()` on every recomposition, so each one started a new database query.

## Decision

- **Playback state is read narrowly.** Screens collect one derived, `distinctUntilChanged` field through
  `PlaybackController.rememberField` (the shell's `ShellPlayback`, the Queue's `isPlaying`/`progress`, the Shorts reel's
  `ReelPlayback`, the skip-silence preview's title). Players and the
  mini player are composed under `SteadyPlayback`: a state with the position fields zeroed, plus `LocalLivePosition`,
  read only by the leaves that show position (seek bar, transcript, skip section, mini-player progress). The video
  stage's buffer-ahead gauge reads it in its own small scope (`BufferAheadProbe`) and hands the controls a `State`. The
  sleep timer's countdown reaches its control as a `State` too, so a running timer no longer recomposes the player
  each second.
- **Values that change are dynamic locals; lambdas given to locals are remembered.** Play and download states are a
  `State<Map>` in a static local; a row reads its own entry through `playStateOf` / `downloadStateOf`
  (`derivedStateOf`), and a feed filters through `filteredByProgress`, so another item's progress redraws nothing. The
  Queue's rows read their own entry the same way, its offline summary reads the map in its own scope, and the summary's
  per-item lookup is a map rather than a scan of the queue for every item. Because a download change no longer redraws
  the whole Queue, its "unavailable offline" wording follows the network through `AppContainer.offlineChanges` (a
  `ConnectivityManager` default-network callback) instead of a read that only refreshed on a redraw.
- **Video draws into a `SurfaceView`** (`SURFACE_TYPE_SURFACE_VIEW`) in the player, the floating window and the Shorts
  reel, so the compositor shows frames without the app redrawing.
- **Nothing animates unseen.** The equaliser steps at 8 fps only while playing and not covered by the full player or
  the Shorts reel (`LocalAnimationsHidden`), and waits for a frame each step, so it stops with the app in the
  background. Over the full player and the Shorts reel the busy bar is a still line rather than an animation: it still
  says "working" from every screen, as Dewi asked, without a redraw per frame.
- **Idle means idle.** The controller's position ticker suspends while nothing plays (`PlayingSignal`) and wakes on
  `onIsPlayingChanged`; both edges are logged ("position ticker idle until something plays" / "running again").
- Release builds are `profileable` from the shell, so a phone can be traced without a debug build.
- **The Queue's large title moves only while the list is scrolled** (or to expand at the very top). Its height sets
  the list's viewport, so following every position change fed back into itself: whenever the rows overflowed the
  viewport by less than the 48 dp the title gives back, title and list flipped every frame for ever. v0.1.635 does it
  too (seen at CI's 640 dp screen; `QueueTitleSettlesAtAnyHeightTest` sweeps 300–900 dp and was red at 426 dp).
  Known and unchanged: once collapsed, the title does not re-expand on returning to the top, because the jump to the
  playing item leaves the list one row below its zero-height top spacer (v0.1.635 opens collapsed for the same reason).
- **Recompositions are counted.** `CountRecompositions` in the shell, Queue, full player, video stage and dock. Every
  30 s snapshot drains the counts, and when any screen recomposed 20 times or more in that window (a per-tick screen
  does 60) the line adds "busy recompositions in the last 30s: …", so a report from the phone shows a screen
  recomposing per tick again without breaking the snapshot's collapsing of repeated lines.

## Consequences

- Emulator, 15 s, v0.1.635 against this change (`tools/emulator/ui-perf.sh`): player open over Queue, frames over
  50 ms 30 → 0, worst 333 → 12.5 ms, main thread busy 9.3 s → 0.4 s; fling through an 1,800-item queue, frames over
  50 ms 35 → 0, worst 413 → 47 ms, main thread 10.2 s → 4.5 s. Not yet re-traced on the 4a.
- A screen that collects the whole `PlaybackState` again brings the stutter back; read a derived field or go through
  `SteadyPlayback`. `PlaybackTicksRedrawNoScreenTest` drives five ticks through the shell with three queued rows under the full player
  and a stub player, so the video stage composes, and expects no screen to recompose (red with the Queue reading the
  whole state, queue 1 → 6, and with the stage reading the position, stage 1 → 6);
  `QueueOfflineWordingFollowsTheNetworkTest` guards the offline wording; `RowReadsOnlyItsOwnStateTest` guards the per-row reads, `PlayingSignalTest` the idle
  ticker and `PlayingEqualiserTest` the equaliser's paused clock.
- A `SurfaceView` sits behind the window: anything opaque drawn over the video area hides the picture, and it ignores
  an ancestor's alpha, so while the stage is dragged down to dismiss the picture may stay opaque as the page fades.
  Screenshots on the emulator show the video black with either surface type, so check on a device: drag to dismiss,
  fullscreen on and off, into the floating window, and paging through Shorts.
- Also fixed on the way: a seek-bar drag was reset by every position tick, so a release just after one lost the seek;
  the drag now lasts until it ends, the item changes, or the slider leaves (the same item reloading drops its duration
  for a moment), so a cut-off drag cannot freeze the bar (`SeekSurvivesATickTest`, both cases).
- `rememberField`'s lambda must be a pure read of the state: it is applied to each emission, so one that read anything
  else would only refresh when playback state next changed.
