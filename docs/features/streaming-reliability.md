---
title: Streaming reliability — chunked fetch, codec choice, expired-URL recovery
kind: feature
status: shipped
area: playback
updated: 2026-08-13
---

# Making video actually play

Three separate faults, all of which looked like "buffering" to the person holding the
phone. Diagnosed 2026-07-27/28 from real crash reports plus emulator reproduction.

## 1. YouTube throttles an open-ended GET to roughly playback rate

The headline one. Measured against a real stream:

| Request shape | Sustained |
|---|---|
| Open-ended GET (what the player did) | 122–178 KB/s |
| Ranged GET, same bytes | 414 KB/s – 1.1 MB/s |

The 1080p H.264 stream the app had chosen needs **166 KB/s** — inside the throttled band,
so it was being fed at almost exactly real time with no headroom. It covered about seven
seconds on the opening burst, then stalled for twenty-odd, repeatedly, at the same position
on a Pixel 7 and on the emulator.

None of it was bandwidth. The connection carried ten times what the stream needed the
moment a request was bounded.

`ChunkedDataSource` (`:core:playback`) wraps the HTTP source and fetches **2MB ranges**
instead of one open-ended GET. Transparent: ExoPlayer opens once and reads to the end, and
each exhausted chunk opens the next range underneath. Every serious YouTube client does
this; it is why they do not stall.

## 2. Codec preference was picking the biggest stream

Preference was AVC-first unconditionally, reasoning that AVC is most likely to be
hardware-accelerated. But on the test video AVC is **1328 kbps against AV1's 853** — 36%
more bytes for the same picture, and those bytes were the difference between playing and
stalling.

Now hardware-aware: the efficient codec wins **when the device decodes it in hardware**
(`MediaCodecInfo.isHardwareAccelerated`), and the old order stands when it does not,
because software AV1 is worse than hardware AVC in every way. The emulator correctly picks
VP9 (hardware VP9, software-only AV1); a Pixel 7 picks AV1.

## 3. A signed URL is a lease, and nothing renewed it

A streaming URL expires in hours. Pause overnight, press play in the morning, and every
request is 403 — which the player reported as a generic source error and retried forever.
Real report (0.1.170): paused 23:50 at 35 minutes in, resumed 06:07, then **seventeen
identical 403s** and two manual seeks trying to shake it loose.

The queue has always held the stable watch URL rather than the signed one, so a fresh URL
was one re-resolve away. Nothing asked for it.

`Media3PlaybackController` now publishes a `StreamFailure`, and `StreamRecovery`
(`:app`) replays the current item from where it stopped, through `PlaybackQueue`'s normal
routing — which is what re-resolves. One seam, both pillars: podcast enclosures move and
expire too.

**Detection matches the cause chain, not the error code.** The report carried
`ERROR_CODE_IO_UNSPECIFIED` with the 403 buried two levels down, so matching the top-level
code would have found nothing. 403 (expired signature) and 410 (retired URL) qualify; 404
and 5xx do not, because the content is gone or a fresh URL fails identically.

**The retry budget is per stuck point, not per item.** Three attempts, but real progress
since the last failure earns a fresh budget — a long listen legitimately crosses more than
one lease. Without that, three expiries in one sitting would disable recovery for good.

### A stuck point also ends when a human picks the item (0.1.383, 2026-08-13)

Dewi: *"warfronts video not playing????? skipping to another 'rest is politics' video for
some reason?????"*. The report answers it exactly, and the answer is three separate
defects compounding around one genuinely bad URL.

The video's signed **video track** 403'd from the first byte while its audio track was
fine, across four independent fresh extractions. That much is YouTube's, not the app's —
and recovery is built for it, and *worked*: attempt 2's fresh URL played the video
normally at 21:03:28. What followed is all ours.

1. **A waiting retry tore down the stream that had already recovered.** Attempt 3 armed a
   4-second backoff at 21:03:28.467, 370ms before attempt 2's replay reached
   `playing at 1ms`. It fired anyway at 21:03:32, forgot the good URL, re-resolved, and
   restarted a healthy stream 8 seconds in — straight into a 403. Both guards now live
   after the `delay`: if a fresh start has happened, or the item is playing again, the
   attempt is dropped and (for the latter) the budget is handed back.
2. **The budget was never reset when he chose the item himself.** After the give-up,
   `attempts` stayed at 3 and `lastPositionMs` at 6063 — so both hand-taps that followed
   hit `attempts >= maxAttempts` on their **first** error and skipped instantly. Two taps,
   two `ERROR` lines, zero `re-resolving` lines between them, straight back to the next
   video in the queue. That *is* the complaint. `PlaybackQueue` now publishes
   `freshStarts` — every play that is somebody's intent, which is all of them except
   recovery's own replay — and recovery starts over on each.
3. **The dead URL stayed cached, so the tap was doomed before it began.** `forget()` was
   only reached from `replayCurrent`, so `21:03:48.706 cache hit for ytZiDr1NLQc (play),
   skipped extraction` handed back the address that had failed four times seconds earlier.
   Recovery now forgets on *every* failure, before it decides whether to retry at all.

Each fix carries a guard test proven to fail against the old code, plus
`TappingAFailedItemAgainTest`, which wires the real queue to the real recovery — because
all three parts were individually defensible and the answer was still wrong.

## Measured outcome

Emulator, the video that previously stalled every seven seconds:

```
before   stall at 7.3s, 23157ms to recover, repeatedly
after    one stall at 39.6s, 65ms to recover
```

Pixel 7, from a real diagnostics report on 0.1.170 (chunked + AV1, before recovery shipped):

```
6GaYPinp4No   ready 542ms → 316ms    ~100,462 kbps
gUarhwho0f8   ready 531ms → 1043ms   ~103,705 kbps
JLNsvr-EoEU   ready 527ms → 1774ms   ~134,217 kbps
```

Against ~2,400 kbps before. Sub-second buffering, AV1 selected.

## Known gap

A fourth video in that same report 403'd at **position 0ms**, seconds after a successful
resolve — not expiry. That video is public, un-gated, and serves bytes fine from a laptop,
both ranged and unbounded, so the chunked probe is not the cause. Unreproduced; plausibly
rate-limiting after 24 resolves against a 58-item queue, but that is speculation. Recovery
bounds it at three attempts instead of an infinite loop.

**Still open, and it recurred on 0.1.383** — same shape, on `ytZiDr1NLQc`: the video track
403'd at 0ms across four fresh extractions while the audio track loaded, and then a fifth
resolve played it. So the URL is not permanently bad, which rules out the video itself and
points at something per-request. What we now know that we did not before: it is
**track-specific** (video 403s, audio does not) and **survives re-extraction**, so a
retry ladder is the right shape of answer even though the cause is unidentified. The
0.1.383 work was all about surviving it gracefully rather than diagnosing it.

Separately: the app used to sit on an unplayable video rather than advancing. Raised with
Dewi 2026-07-28, who said "maybe ok for now" — then fixed later the same day once it paired
naturally with the download retry loop, since both were a permanent failure treated as
retryable. See [failure-handling.md](failure-handling.md).

## Files

- `core/playback/…/ChunkedDataSource.kt` — the range-fetching wrapper
- `core/playback/…/Media3PlaybackController.kt` — `looksExpired()`, `isExpiredStatus()`, `StreamFailure` emission
- `core/playback/…/StreamFailure.kt`
- `app/…/playback/StreamRecovery.kt` — the retry budget, the fresh-start reset, the
  post-backoff guards (named `ExpiredStreamRecovery` when this doc was written; it grew the
  unreachable case and was renamed, and the doc had not kept up)
- `app/…/queue/PlaybackQueue.kt` — `freshStarts`, and `forgetResolved` shared by the
  failure path and the replay
- `app/…/video/VideoCodecSupport.kt`, `VideoQuality.kt` — hardware-aware preference

## Tests

- `StreamRecoveryTest` — position, budget exhaustion, per-item budgets, progress resetting
  the budget, a replay that cannot start, and (0.1.383) a tap earning a whole new budget, a
  waiting retry dropped once the item plays again or once something else starts, and every
  failure forgetting its stream
- `TappingAFailedItemAgainTest` — the queue and the recovery wired as `AppContainer` wires
  them: the tap reaches recovery, a replay does not masquerade as one, and the dead address
  is dropped on the failure rather than on the way into a retry
- `ExpiredStatusTest` — which HTTP statuses earn a re-resolve

**Not covered:** the cause-chain walk in `looksExpired()`. Building a Media3
`InvalidResponseCodeException` needs an `android.net.Uri`, which a JVM test cannot make, so
the status codes are tested and the walk is not.

---

# Appendix: the AppShell overlay category

Not streaming, but the same shape of bug and found the same way, so recorded next to it.

`AppShell` renders three things as overlays in its `Box`, **outside** the `Scaffold`: the
full player, the shorts reel, and the channel screen. Tab content is inset by the Scaffold's
`innerPadding`; an overlay gets nothing. The player and the reel inset themselves
deliberately (they are full-bleed video). The channel screen did not, so its title and
Subscribe button drew under the clock and battery icons.

What hid it: the *same* screen opened from inside the Videos or Search tab is correctly
padded, so it looks right by every route except "go to channel" from a row — the most common
one. Fixed at the call site rather than inside `ChannelScreen`, since the other two call
sites already receive padding and doing it internally would double it there.

An audit of the rest came back clean: every screen that takes a `modifier` applies it, and
the overlay list is the only category that bypasses the Scaffold. Dewi said "some screens",
plural, so **one is proven and fixed and the rest is an argument** — if another turns up,
the overlay list is the first place to look, but the audit is not the same as having seen it.

## The ranged fetch had a tail bug of its own (2026-08-06)

The fix above is right and stays. Two defects in how it was *implemented* were found from reports
`20260806T1843/1844`, both at the very end of a stream:

1. **"Bytes remaining" was read from `clen`, the length of the whole resource**, while the caller
   could be starting partway through it — so a read resumed at byte P over-declared itself by exactly
   P and then asked for a range past the end. ExoPlayer restarts its loader at a non-zero offset on
   every seek and every load-control pause, so this was nearly every read.
2. **A range that produced nothing was re-requested, recursively, without bound.** Asked for a range
   at or past the end, googlevideo can answer with no bytes rather than a refusal — and a refusal
   would at least have been a load error. So a single `read()` could spin forever: no bytes, no
   completion, no cancellation, no error, and everything it held retained.

The arithmetic and the stopping rule now live in `ChunkedRead`, a pure state machine, because they
are the part worth testing and the data source could not be tested at all without a device. Both were found while investigating stalls at the end of an item, and neither turns out to cause
them — reverting each and re-running the flow against a real YouTube stream still plays to the end.
They are fixed on their own merits. See `../todos/stalls-near-the-end-of-an-item.md` for the evidence
and for where the cause is now being looked for.
