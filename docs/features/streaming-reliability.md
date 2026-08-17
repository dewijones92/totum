---
title: Streaming reliability — chunked fetch, codec choice, expired-URL recovery
kind: feature
status: shipped
area: playback
updated: 2026-08-17
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

### The audio was on the disk the whole time (2026-08-14)

A fourth thing the same report showed, raised with Dewi and settled by him: the WarFronts video was
**already downloaded audio-only** (`copy=audio-only`, all 29 queue items ready), and the app skipped
past it three times without reaching for the file.

That was `routeNow` behaving as designed — an audio-only copy does not silently take the picture
away while you are watching. The rule assumes a working stream to prefer, though, and once every
retry has failed the comparison is no longer "audio or video" but **"audio or nothing"**, where
skipping is the worse answer. So `routeNow` gained `streamRefused`, which is the fourth reason an
audio-only copy is worth playing, alongside offline / Listen mode / it being a podcast — one seam,
both pillars, and the watching rule untouched while the stream is fine.

Recovery asks for it *after* its budget is spent and *before* it moves on. Reaching that state with
nothing on the disk is a new `Refusal.StreamWillNotPlay`, which is when moving on is right.

### Only the newest play wins (2026-08-14)

An extraction is 5–11 seconds on a phone and taps arrive during it. The resolver already
de-duplicates the *extraction* — a second caller joins the first — but every joined caller then went
on to play: three taps four seconds apart produced the same video handed to the player **three times
in 81ms**, with three `beginSession` calls to YouTube. `VideoPlaybackLauncher` now stamps each
request and only the newest may start playback.

A superseded request returns `true`, not `false`. False would make an auto-advance treat the item as
unplayable and skip to the *next* one, so it would fight whatever the user had just chosen — worse
than the duplicate it replaced.

### A 403 is not always an expiry, and calling it one cost 40 seconds a go (0.1.390, 2026-08-17)

Dewi, on three reports in one evening: *"buffering???? weird stuff??"*, *"more buffering"*. The
numbers agreed with him — `bufferingMs = 60290`, `abandonedBufferingMs = 44574`, 31 stalls in 44
minutes — and **all** of the abandoned time was recovery spinning on a diagnosis that was wrong.

The URL that failed at 19:31:02 BST:

```
…/videoplayback?expire=1787013060&ei=ZFODaqi5D82KoccP9pC4sA8&itag=140&c=ANDROID_VR&…
```

`expire=1787013060` is **00:31:00Z the next morning — nearly six hours away**. The lease was fine.
`isExpiredStatus` maps every 403 to `Expired`, so recovery believed a fresh URL would fix it and
spent its full budget finding out otherwise: `expire=…066`, then `…073`, then `…081`, each newly
signed, each refused within 150ms, each costing 12–18 seconds of Python extraction. Then it gave up
and reached for the disk — where the audio had been the whole time.

So the split is now on the timestamp the URL carries, not on the status code:

| | Meaning | Budget |
|---|---|---|
| `Expired` | 403/410 and the lease has run out (or there is no lease to read) | 3 attempts |
| `Rejected` | 403/410 and the lease is still in the future — the stream is being refused | 1 attempt |

One attempt is kept for `Rejected` because a single bad CDN node is real and a fresh URL can land
elsewhere. What is gone is arguing with a client YouTube is turning away. 0.1.170's overnight pause
is a genuine expiry and keeps its three.

The trail said the wrong thing too, which is how this survived: every retry line read
`"re-resolving expired stream"` regardless, so the report asserted the false diagnosis fourteen
times. It now names the reason, the budget, and — at the point of judgement — the **inputs**: the
status, the seconds of lease remaining, and the client that signed the address.

**The root cause is still unidentified, and now instrumented rather than guessed at.** Every refused
URL in 0.1.390 was `c=ANDROID_VR` (one of yt-dlp's own default clients, not one we ask for), and
every refusal was on a range request deep into the item — 1689219ms of 2260648ms on a resume, then
1800024ms. That pairing is a plausible cause and no more than that, so rather than reaching for
`player_client=-android_vr` on a hunch, `playback.refusedBy.<CLIENT>` counts refusals per client and
the next report decides. See also the `youtube-android-client-first-megabyte` note.

### A stale resolve took playback off the disk and back onto a 403 (0.1.390)

The single worst thing in the same report, and the direct answer to "more buffering":

```
20:56:17.066 queue play-at-4 "Discoveries That Confirmed Ancient Folklore"   (watching)
20:56:17.075 route -> streaming the video                                     resolve begins
20:56:19.351 settings playbackMode -> AUDIO
20:56:19.359 route -> the downloaded audio at /data/…/3138547848.media
20:56:19.868 ready after 464ms — playing                                      ✅ from disk
20:56:29.286 engine extract … in 12210ms                                      the OLD resolve lands
20:56:29.548 listening — audio track preferred
20:56:29.548 play … from https://…googlevideo.com/videoplayback?…             ❌ file dropped
20:56:32.857 gave up buffering after 3308ms — it never recovered
```

"Only the newest play wins" was already in place and *held* — but it counted only the launcher's own
plays. A route to a file goes straight to `PlaybackController.play`, so it never claimed the token,
and a twelve-second extraction that was twelve seconds stale on arrival still believed it was the
newest thing anybody wanted.

`beginPlay()` is now public and `PlaybackQueue.route` claims it for **every** route before choosing
one, passing the token into `launcher.play`. Every other hand-off to the player claims it too
(`playLocal`, `listen`, `playVideoQuality`), so switching to Listen by hand or changing quality also
supersedes a resolve in flight — and `selectAudioTrack`, which re-extracts and so waits just as
long, checks the token after its re-pick.

### "The tail is not coming" was crying wolf 33 times a report (0.1.390)

8% of a bounded 400-event buffer, and not one of them a fault:

```
20:18:24 stopped loading at 1830782ms with only 27484ms buffered ahead and 402334ms never fetched
20:18:30 stopped loading at 1836696ms with only 23370ms buffered ahead and 400534ms never fetched
```

Twenty-five seconds buffered, playback healthy, recurring every few seconds as the buffer drained
and refilled. The test compared `ahead` against `BufferBudget.MIN_BUFFER_MS` — which is the level
*below which loading resumes*, not a level that means anything is wrong — and `PLAYBACK_BYTES` puts
30 seconds out of reach for a 1080p AV1 stream anyway, so the buffer settles just under the target
and every ordinary pause looked like a lost tail. `loadsStoppedShort = 92` was therefore a number
measuring nothing.

`loadStopIsAFault` now asks the question that distinguishes 0.1.359's real case (70ms buffered,
stalled, 35 seconds unfetched): did the stop leave playback **unable to carry on** — stalled, or
under a second ahead? What the 33 lines were worth saying is kept as one gauge,
`playback.leastAheadAtLoadStop`, which is what would have shown the byte ceiling binding at ~25s
against a 30s target without anyone reading the code.

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
- `core/playback/…/Media3PlaybackController.kt` — `deadAddressReason()`, `leaseVerdict()`,
  `leaseSecondsLeft()`, `streamClient()`, `isExpiredStatus()`, `StreamFailure` emission
- `core/playback/…/PlaybackDiagnostics.kt` — `loadStopIsAFault()` and the
  `playback.leastAheadAtLoadStop` gauge
- `app/…/video/VideoPlaybackLauncher.kt` — `beginPlay()`, and the token check after every resolve
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
- `StreamLeaseVerdictTest` — expiry vs refusal read off the URL's own `expire`, on the real
  parameters from 0.1.390, plus the signing client
- `ARefusedStreamStopsRetryingTest` — a refusal gets one attempt and then the disk; an expiry keeps
  its three; progress since the last refusal earns a fresh one
- `LoadStopIsAFaultTest` — the 33 false lines from 0.1.390 and the one real case from 0.1.359
- `AStaleResolveDoesNotClobberPlaybackTest` — the queue, launcher, resolver and controller wired
  together: a file that has started playing survives a resolve landing ten seconds late

**Not covered:** the cause-chain walk in `deadAddressReason()`. Building a Media3
`InvalidResponseCodeException` needs an `android.net.Uri`, which a JVM test cannot make, so
the status codes, the lease judgement and the client parse are tested and the walk is not.

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
