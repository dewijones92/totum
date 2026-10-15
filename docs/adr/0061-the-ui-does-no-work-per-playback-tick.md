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
  read only by the leaves that show position (seek bar, transcript, skip section, mini-player progress).
- **Values that change are dynamic locals; lambdas given to locals are remembered.** Play and download states are a
  `State<Map>` in a static local; a row reads its own entry through `playStateOf` / `downloadStateOf`
  (`derivedStateOf`), and a feed filters through `filteredByProgress`, so another item's progress redraws nothing. The
  Queue, whose summary needs every item, reads that same app-wide state instead of opening its own query.
- **Video draws into a `SurfaceView`** (`SURFACE_TYPE_SURFACE_VIEW`) in the player, the floating window and the Shorts
  reel, so the compositor shows frames without the app redrawing.
- **Nothing animates unseen.** The equaliser steps at 8 fps only while playing and not covered by the full player
  (`LocalAnimationsHidden`). Over the full player the busy bar is a still line rather than an animation: it still says
  "working" from every screen, as Dewi asked, without a redraw per frame.
- **Idle means idle.** The controller's position ticker suspends while nothing plays (`PlayingSignal`) and wakes on
  `onIsPlayingChanged`; both edges are logged ("position ticker idle until something plays" / "running again").
- Release builds are `profileable` from the shell, so a phone can be traced without a debug build.

## Consequences

- Emulator, 15 s, v0.1.635 against this change (`tools/emulator/ui-perf.sh`): player open over Queue, frames over
  50 ms 30 → 0, worst 333 → 12.5 ms, main thread busy 9.3 s → 0.4 s; fling through an 1,800-item queue, frames over
  50 ms 35 → 0, worst 413 → 47 ms, main thread 10.2 s → 4.5 s. Not yet re-traced on the 4a.
- A screen that collects the whole `PlaybackState` again brings the stutter back; read a derived field or go through
  `SteadyPlayback`. `RowReadsOnlyItsOwnStateTest` guards the per-row reads and `PlayingSignalTest` the idle ticker.
- A `SurfaceView` sits behind the window: anything opaque drawn over the video area hides the picture. Screenshots on
  the emulator show the video black with either surface type, so check the picture on a device.
