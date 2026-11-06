---
title: Starting an album or group switches Repeat one off
kind: todo
status: built (tests owed)
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

## Built 2026-11-06 (code first; tests owed)

`RepeatOneRule` and `QueueNotice` (`app/…/queue/RepeatOneRule.kt`), the rule inside `PlaybackQueue.playAll` for a run of
a group started by you, the toast in `ui/common/QueueNoticeToasts.kt`, and ADR 27's amendment. Shorts-reel re-binds
(`byYou = false`) and the kid lock are left alone. The diagnostics state block already carried both `settings.repeatMode`
and `playback.repeatNow`, so nothing was added there; the rule logs each decision under `[queue]`.

Tests owed (testing stretch): `RepeatOneRuleTest` (ONE becomes OFF and says so; other modes and kid lock untouched);
`PlaybackQueueTest`: `playAll` with a group and `byYou` switches it off and emits the notice, with no group or
`byYou = false` does not (red against the old queue, where repeat stayed ONE); album and artist shuffle end to end.
