---
title: Buffers towards the end of the video
kind: todo
status: not seen in the wild since 0.1.477 (10 phone reports); cause not established; the detector that should have caught a recurrence was crying wolf and is fixed
area: playback
updated: 2026-09-25
---

# "Buffers towards the end of the video??????"

Dewi, 2026-08-06, after an evening in his queue: *"rest is politics in my queue buffers towards the
end of the video?????? obvs what the problem/solution is???"*.

His observation was exact. Reports `20260806T184333` and `20260806T184426` (0.1.359, commit
`a5355c9`, Pixel 7, Android 16) cover 52 minutes and contain **four consecutive items that each
hard-stalled inside their last 45 seconds and never recovered**:

| Item | Stalled at | Left of the item | Buffered ahead | Outcome |
|---|---|---|---|---|
| `chxbS3N3Llc` | 3,386,518ms | 42.8s | 82ms | rescued, then ended at 444ms left |
| `Xr9VqRawjAU` | 1,013,053ms | 11.5s | 68ms | given up on, treated as ended |
| `Ui8jZQirfj0` | 3,692,530ms | 35.8s | 70ms | rescued, then ended at 120ms left |
| `Ui8jZQirfj0` | 3,728,246ms | 0.1s | 87ms | treated as ended |

`playback.abandonedBufferingMs = 208530` of `bufferingMs = 244115`: **85% of all buffering in the
session was time that never resolved.** Two stalls of 20–26 seconds per item, every item.

## What the report proves on its own

- **Loading stopped completely, rather than going slowly.** Between 19:41:29 (80,484ms buffered) and
  19:42:22 the buffer drained at exactly playback rate — 80 seconds of media in 53 seconds of wall
  clock at 1.5×. Zero bytes arrived in that window.
- **Loads are leaking, and it is not an accounting artefact.** `playback.loadsOutstanding` climbed
  35 → 37 across 53 seconds and only ever climbs; `oldestLoadStartedAt` was byte-identical in both
  reports, and the stall lines put the oldest outstanding load's age at **25,489,806ms — seven
  hours**. Only 17 loads completed in the whole session for 53MB, against 66 cancellations.
- **Nothing errored.** `playback.loadErrors` sat at 14 and `playback.errors` at 12 across both
  reports, unchanged through every stall. So these loads did not fail — they simply never ended.
- **The heap is a consequence, not a coincidence.** 102MB holding 234s of buffer at 18:51; 255MB of
  256MB holding 80s at 19:41. Same workload, 150MB more heap. logcat over the stall shows
  `Clamp target GC heap from 323MB to 256MB` and repeated `Waiting for a blocking GC Alloc`.

## The two defects found, both fixed

### 1. `ChunkedDataSource` over-declared how much was left

`open()` set "bytes remaining" from the URL's `clen` — the length of the **whole resource** — while
the caller's `DataSpec` could start partway through it. So a read resumed at byte P over-declared
itself by exactly P, and on reaching the true end still believed P bytes were owed, and asked for a
range past it.

This is not a resumed-playback edge case. **ExoPlayer restarts its loader at a non-zero byte offset
on every seek AND every time the load control pauses loading**, so nearly every read of a long item
began this way. It has been live since the ranged-fetch change (`dcb8dbc`).

Fixed by `remainingFrom` in `ChunkedRead.kt`, which keeps the three quantities apart: a length the
caller stated is already relative to their position, `clen` is not, and a probe answers from the
position already.

### 2. A range that produced nothing was asked for again, forever

The end-of-input branch re-opened the same range and **called `read()` recursively**, with the only
stopping condition being `remaining == 0` — which the defect above guaranteed was false. Asked for a
range at or past the end, googlevideo can answer with **no bytes rather than a refusal**; a refusal
would at least have surfaced as a load error. Nothing at all meant one `read()` spun indefinitely:
no bytes, no completion, no cancellation, no error, and every buffer it held retained.

That is exactly the shape the counters show. Fixed twice over: `ChunkedRead.endOfRange()` refuses to
continue past a range that produced nothing or stopped short, and `read()` is now a bounded loop
rather than recursive.

### 3. (Same reports) the preloader could never let go — see `offline-queue.md`

`releaseIfPlaying` compared the **held URL** with the **playing URL**. A stream URL is re-resolved
per play and comes back signed, expiring and often at a different itag, so those two are routinely
different — and the report has three `still holding … — what started is …` lines where both URLs are
the same video at itags 18 and 399. So it never released, on a heap already at 255MB of 256MB. Now
keyed on the item id. The nomination resolving a *different format* from the one that plays is a
separate waste, now logged and counted (`playback.preloadsWasted`) rather than silent.

## These two defects are NOT the cause — tested, twice

Stated plainly because the temptation to leave it implied is exactly how a wrong root cause gets
believed.

Both fixes were reverted and the flow re-run at two levels:

| Test | Media | With both defects reinstated |
|---|---|---|
| `StreamPlaysToItsEndTest` | generated WAV over localhost, resumed 3s from the end | **passes** |
| `LiveStreamPlaysToItsEndTest` | a real YouTube stream, resumed 6s from the end | **passes** |

The first was explainable: a WAV carries explicit sample sizes, so the extractor stops at the last
sample and never reads to the data source's end-of-input, which is where the defect bit. That
predicted the live one against real `gir=yes` streams would fail. **It did not.** So the format
hypothesis is disproven too, and with it the claim that these defects caused the stalls.

They are still real defects, each proven to fail without its fix at two levels — the arithmetic
(`ChunkedReadTest`, 6 of 18 cases; `ChunkedDataSourceTest`, 2 of 6) and the unbounded loop
(`ChunkedDataSourceTest`'s read cap tripping). They are fixed on their own merits. **The cause of the
end-of-item stalls remains open.**

## What the evidence still says about the cause

Narrowed, but not closed:

- The buffered position **stopped advancing about 35 seconds short of the duration** and never moved
  again; the playhead simply caught up to it. Derived: at 19:41:29 the buffer held 80,484ms ahead of
  position ~3,613,030ms, so it reached ~3,693,514ms of a 3,728,366ms item.
- **The load control wanted to load.** At 80s buffered with `playbackSpeed` at 6.0 (skip-silence),
  `DefaultLoadControl` scales its minimum to 180s, so `bufferedDurationUs` was below the minimum and
  `prioritizeTimeOverSizeThresholds` (true by default) makes it load regardless of the byte ceiling.
  So this is not the buffer budget refusing to fetch.
- Which leaves two candidates, and they have opposite fixes: the extractor reported end-of-input
  ~35s early and the player believes the item is fully fetched, or a loader is genuinely stuck. The
  monotonically climbing `loadsOutstanding` with a seven-hour-old oldest load and zero load errors
  points at the second, but that counter has been an accounting artefact before (0.1.306).
- Worth suspecting next: `MergingMediaSource` takes the **minimum** buffered position of the video
  and audio sources, so one stream ending or stalling early pins the merged figure while the other
  is healthy. Nothing currently reports the two halves separately.

## What the next report will settle

Per the repo's rule that every change must be provable in the wild from its logs alone, the
instrumentation that was missing has been added:

- **`playback.loadsInFlight`** — the outstanding loads *named*: track type, start time and host, for
  the oldest few. "37 in flight, oldest seven hours" could not say whether the stuck stream was the
  video, the audio or a subtitle, and those have different fixes.
- **`stopped loading … with only Nms buffered ahead and Nms of the item never fetched — the tail is
  not coming`** (`playback.loadsStoppedShort`) — fires only when the player stops fetching with a
  nearly-empty buffer and content still to come, so it is the anomaly and not the load control's
  ordinary pauses, which are counted instead (`playback.loadPauses`).
- **`stream ended while it still owed bytes`** (`playback.streamsEndedEarly`) — the data source
  saying so itself, with its numbers.

If the stalls persist, the next report says which track is stuck and whether the fetch gave up
believing it was finished. If they stop, `streamsEndedEarly` says how often the tail was rescued.

## Related

- `../features/streaming-reliability.md` — the ranged fetch this corrects.
- `../features/offline-queue.md` — the preloader.
- `high-quality-playback-fix.md` — the format choice that makes `gir=yes` streams the norm.

## 2026-09-25: a month of phone reports, and an instrument that was crying wolf

Every Pixel report on the Pi was read (92 in total; the ten from 0.1.477 to 0.1.514 are the ones that
bear on this):

| | 0.1.359 (the original) | 0.1.477 → 0.1.514 |
|---|---|---|
| `playback.abandonedBufferingMs` | 208,530 of 244,115 | at most **1,272** in any report; `-` (none) in most |
| `gave up buffering … never recovered` near the end of an item | four in a row | none |

So the end-of-item stall has **not recurred in a month**. That is weak evidence, not a fix. Most of
that listening played **downloaded files** (`playing.route=a local file`, `downloads.onDisk=355`),
which is not the streaming path the stall lived on, and no code change has been shown to remove the
cause. The item stays open. Absence of a report is not a control.

**What did change is that the instrument meant to catch a recurrence was producing only noise.**
`loadsStoppedShort` was non-zero in almost every recent report, which read like the bug. Every line
looked like this (0.1.496, 20 September):

```
09:06:55.708 stopped loading at 77700ms with only 247661ms buffered ahead … the tail is not coming
09:06:57.096 stopped loading at 67714ms with only 244967ms buffered ahead …
09:06:57.558 stopped loading at 37714ms …   (and three more, 150 ms apart)
```

That is four MINUTES buffered, and six lines inside two seconds at 10 s steps back: someone tapping
rewind. `loadStopIsAFault` treated ANY load stop while the player was buffering as a fault, and every
seek buffers for a moment with a full buffer. So it judges on the buffer alone now. A second check,
at the moment a stall BEGINS, catches the original shape: loading had already stopped and the buffer
ran dry. It logs `stalled at … with loading already stopped … the tail is not coming` and counts
`playback.stallsWithLoadingStopped`.

Tests: `LoadStopIsAFaultTest` (the seek numbers above are now a non-fault) and
`AStallWithLoadingStoppedTest` (0.1.359's numbers are a fault, a seek is not, a buffer still loading is
not). Both failed first.

**Corrected the same day, after an Opus review.** The first version of the stall check judged at the
moment BUFFERING began. That is exactly when `MediaController` MASKS a seek: it reports
`STATE_BUFFERING` at once, with the buffered position set to the new position and `isLoading`
unchanged. So every rewind would have logged "the tail is not coming" again, under a new name. The
unit test fed in the settled state and could not see that. Now the check snapshots the state at onset
and judges only a stall that has LASTED at least 2 s (`STALL_WORTH_JUDGING_MS`); a masked seek recovers
in milliseconds. A lasting stall with seconds buffered and nothing loading gets its own line:
`… not waiting on the network, so the decoder or renderer is the suspect`
(`playback.stallsNotWaitingOnTheNetwork`). The old rule used to mislabel that shape, the Fire Stick
h264 wedge for example, as a lost tail. **If this recurs, the next report now says so in one line, instead of hiding
it among forty false ones.**

