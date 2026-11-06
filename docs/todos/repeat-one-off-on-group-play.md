---
title: Starting an album or group switches Repeat one off
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Starting an album or group switches Repeat one off

Report 20261106T084701 (Pixel 4a): an album shuffle played its first track four times; Repeat "This one" was on from
before (`AutoAdvancer.kt:84`). Nothing resets repeat on play-all/shuffle. Decided: starting a play-all or shuffle of an
album, channel, playlist or group switches "This one" off and says so; repeat-the-queue stays. Entry points:
`PlaybackQueue.playAll`, `ShuffleAll`, `ArtistShuffle`, `MusicAlbumViewModel`. Add the repeat mode to the diagnostics
state block.
