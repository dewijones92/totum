---
title: A recovery in flight resumes the NEXT item at the last item's position
kind: todo
status: fixed 2026-09-06 — a failure for an item the queue has moved on from is dropped; guarded by StreamRecoveryTest
area: playback
priority: high
requested: 2026-09-06
updated: 2026-09-06
---

# A recovery in flight resumes the next item at the last item's position

CI run for `d4df786` (2026-08-31): `StalledStreamRecoveryTest` failed with *"the hosted item never
started playing … buffering=true position=39387 item=stalling"*. Locally the same test passes in 31s.
Its own logcat (the `instrumented-reports` artifact) shows why, and it is not the test:

```
20:09:35.253 play a-clip from file:/…/strategy-clip.mp4        ← previous test's item, still failing
20:09:35.294 play-now: … playing=a stream that goes quiet      ← THIS test starts "stalling"
20:09:35.316 fresh start of stalling — recovery starts over
20:09:35.325 transition (playlist-changed) -> a-clip           ← the old item comes back
20:09:35.718 ERROR … at 39387ms — a-clip … Source error
20:09:35.887 stream unreachable at 39387ms — waiting for a network
20:09:35.888 network is back — resuming from 39387ms           ← a-clip's position…
20:09:36.000 play stalling from http://127.0.0.1:34041/media.wav   ← …applied to stalling
20:09:52.182 stopped loading at 39387ms with only 0ms buffered ahead — the tail is not coming
```

`StreamRecovery` for `a-clip` (the item before) was mid-ladder when the queue was cleared and a new
item started. Its "network is back — resuming from 39387ms" fired **after** the new item's fresh start
and re-routed playback with the OLD position, so `stalling` was seeked to 39s of a stream whose test
server only ever serves the first 40KB. The player then buffered there forever.

"Fresh start of stalling — recovery starts over" is logged at 20:09:35.316, so the reset exists — and a
recovery already past that point still completed against the new item. Same shape as
`AStaleResolveDoesNotClobberPlaybackTest` (a late resolve) and the rule in
[staleness-guards-need-every-caller]: every late-arriving recovery step must check it is still about the
item that is playing, not only the resolve.

Why it surfaced now: coincidence of timing with the preceding test's teardown, not the download change
in `d4df786` (which touches no playback code). But the leak is real and a user can hit it — a stream
failing while you tap the next item is the everyday version.

## Fixed (2026-09-06)

Seen twice in CI (the run and its re-run), never locally. `StreamRecovery` now keeps
`currentStartedItem` — set only by `freshStarts`, never by a failure — and drops any failure for a
different item after forgetting its dead URL. `overtaken` could not catch this: the stale failure
arrived *after* the fresh start, so the generation it captured already included the bump, and the
failed item was not the one playing. Test written first and watched red:
`StreamRecoveryTest."a failure for an item the queue has moved on from is ignored"`, with the
positive twin proving the guard does not over-block. `resuming … from` now names the item.
`OfflineQueuePlaybackTest` + `StalledStreamRecoveryTest` then ran green on-device in CI's order.

## Done when (met)

- A unit test on `StreamRecovery`/`PlaybackQueue`: an item's recovery step landing after another item
  has started is dropped and logged, never applied. Watched red first.
- The CI logcat above no longer possible: `resuming from` names the item it resumes, so a report can
  see a mismatch.
