---
title: Permanent vs transient failure, and playback that goes nowhere
kind: feature
status: shipped
area: playback
updated: 2026-08-20
---

# Knowing when to stop asking

The same mistake existed in two places: a failure that can never succeed was treated as
worth retrying, so the app either burned data forever or parked on something dead. Both
found by reading real diagnostics on 2026-07-28.

## Downloads

Two members-only videos sat in a 59-item queue and were re-attempted on **every queue
change, on every launch, for days** — present in every report sent that morning. The
auto-downloader matched `Downloaded` and `Downloading` and let everything else fall through
to "fetch it", so a failure was indistinguishable from never having tried.

`DownloadState.Failed.isPermanent` (`:core:domain`) classifies the extractor's own words,
which is all the failure carries: members-only, private, unavailable, removed, terminated,
age-gated. Age-gating counts because it needs a signed-in fetch the downloader cannot do,
so an unattended retry will not fix it either.

**Deliberately conservative** — anything unrecognised is treated as transient. Wrongly
giving up on a flaky connection is worse than one wasted request, and a 5xx or a timeout
says nothing about the content. Transient failures get a bounded three attempts per
session, not persisted: a fresh launch is a fair reason to try again, and a permanent
failure is refused on its reason regardless of any counter.

### The budget had nothing to spend it (0.1.390, 2026-08-17)

Dewi: *"downloading delayed????"* — the tennis podcast. His phone:

```
20:58:28.723 download start audioOnly=true Cincinnati - Will beaten Djokovic contend…
20:58:31.462 download failed …: Network(detail=ERROR: unable to download video data:
                                 HTTP Error 403: Forbidden)
20:58:37.549 queue move 1->0                     ← he happened to reorder the queue
20:58:37.578 download start audioOnly=true Cincinnati - Will beaten Djokovic contend…
20:58:49.830 download done                       ← the very next attempt worked
```

Everything above this line was right: 403 is not a permanent marker, so three transient
attempts were available. What was missing is anything to *use* them. The class was driven
entirely by `queue.collect`, so a failure could only be reconsidered when the queue itself
changed — and what got the tennis podcast downloaded, six seconds later, was Dewi dragging an
entry. Left alone it would have sat failed until the next launch, which from the outside is a
download that silently never happens.

`download()` is now a loop: fetch, wait for it to settle, and if it settled `Failed`, ask
`skipReason` — the same single place that already decides — whether it is worth another go, then
wait `5s × the retry number` and go round. Sequential still, so the retries do not compete with
playback for the connection, and bounded by the same budget. A settle that *times out* returns null
and is never retried on: a wedged download retried is two wedged downloads.

The retry says which retry it is and what it is recovering from, because "it downloaded" and "it
downloaded on the third go, thirty seconds late" were otherwise the same line — and the delay was
the whole complaint.

## Playback

The expired-stream recovery stopped after three re-resolves — right for the item, wrong for
the session. A real report had the player dead on one video with 58 more behind it, going
nowhere. It now moves to the next entry, and logs when there is nothing left to move to.

Skipping is gated on the retry budget being spent rather than the first failure: a single
403 is usually just an expired lease that re-resolving fixes, so skipping immediately would
throw away the item the recovery exists to save.

## Why both report their reason

"It stopped and I cannot tell you why" is the failure mode all of that day's logging work
existed to kill. So the skip decision says *why* — "asking again cannot help — ERROR: …
Join this channel", "gave up after 3 attempts". Ordinary skips (already downloaded, nothing
to fetch yet) stay silent, so the trail keeps only what would otherwise be mysterious.

## Files

- `core/domain/…/DownloadState.kt` — `isPermanent` and the marker list
- `app/…/queue/QueueAutoDownloader.kt` — `skipReason`, `failureSkip`, the attempt budget, and the
  retry loop that finally spends it
- `app/…/playback/ExpiredStreamRecovery.kt` — `moveOn` when the budget is spent

## Tests

`DownloadFailureTest` (7 cases: each permanent marker, case-insensitivity, network failures
and unknown reasons staying retryable), `QueueAutoDownloaderTest` (+3: permanent never
retried, transient retried, transient bounded), `ExpiredStreamRecoveryTest` (+2: moves on
once spent, does *not* move on while attempts remain — the second matters more, since an
over-eager skip would look like the bug being fixed).

`AFailedDownloadIsTriedAgainTest` (7 cases, 2026-08-17) covers the moment the others could not: a
download that fails *during* a pass. Its fake fails the first N attempts on each item and then
succeeds, which is what a refused stream URL actually does. Four of the seven failed against the old
code; the three that passed are the ones worth keeping honest — a success is not followed by another
attempt, a permanent failure gets none, and the item behind it is still fetched.

Found while writing those: `FakeDownloadManager.emit` fires an event but does **not** touch
the observable state map, so a test driving a consumer of `observeDownloads()` could not see
a failure at all — the first two retry tests passed against state that was never set.
`setFailed` mirrors the existing `setDownloading`.

## Postscript: logic that must outlive the UI (2026-07-28)

Two bugs in one day with the same shape, which makes it a pattern rather than two
accidents: **work the user expects to continue does not belong in a composable.**

1. Row actions started playback on `rememberCoroutineScope()`. Switching tabs cancelled the
   composition and killed an in-flight extraction — a tap became a race against the user's
   next gesture.
2. `AutoAdvance` read playback state through `collectAsStateWithLifecycle()`, which stops
   collecting when the activity stops. With the screen off the composition never saw
   `hasEnded`, so nothing advanced. Proven by a seven-minute gap between an item ending and
   the decision being reached, while 30-second snapshots kept arriving from a plain coroutine
   the whole time.

Both now run on the application scope. The test for whether something belongs there: *would
the user expect this to happen with the phone in their pocket?* If yes, a composable cannot
host it.

Worth noting what makes this hard to catch: neither failed loudly. There was no crash and no
error — just an absence, which is why both needed the diagnostics trail to find at all.

## The third way playback goes nowhere: a stall (2026-07-31)

Dewi, again with the screen off: *"I expected the next item to be played as it finished the
first item, but it didn't auto play."*

This time neither watcher was asleep — both were running and neither had anything to react
to. A 41-minute video reached 2506062ms, **seven seconds from its end**, went to BUFFERING at
07:55:48 and was still at exactly that position 46 seconds later, across two 30-second
snapshots, until he picked the next item by hand. Sixty-five items were queued behind it.

That is a third state, distinct from the two the app already handled:

| What happened | Signal | Who acts |
|---|---|---|
| Item finished | `hasEnded` | `AutoAdvancer` |
| Stream died | `StreamFailure` | `ExpiredStreamRecovery` |
| Item froze | **nothing at all** | `StallWatchdog` |
| Item unreachable | error, but not an expiry | `StreamRecovery` (2026-07-31) |

`StallWatchdog` treats a position that has not moved for 20s while buffering, within 15s of
the duration, as an end and advances. A stall earlier in an item is **logged only** — same
fault, just as fatal in a pocket, but re-resolving mid-item would restart the video every
time a train went through a tunnel, and there is not one observation of it yet to design
against. The log carries the position and the duration so the next report can settle it.

### The thing that nearly shipped doing nothing

The first version collected `PlaybackController.state`. Its tests failed, which is the only
reason this is worth writing down: **`state` is a `StateFlow`, and a `StateFlow` drops a value
equal to the one before it.** A stall is by definition a run of identical states — same item,
same position, same buffering flag — so a collector gets exactly one emission when the stall
starts and then silence. An emission-driven timer would have been read once, at zero elapsed,
and never fired.

Nothing about that failure is observable: no crash, no error, no log line, just a watchdog
that quietly never triggers. It would have looked shipped and fixed. The tests catch it
because they hold the state completely still and let *time* pass, which is what a stall
actually is — so the watchdog samples on a clock instead of collecting.

Generalising: **when the signal you need is "nothing has changed", a conflating flow cannot
carry it.** Sample, don't observe.

## And a fourth: IDLE after a connection failure (2026-07-31)

Found by testing on the emulator rather than reasoning: black-hole the network mid-playback,
restore it, and **the player never comes back**. It sat at exactly 517805ms for over three
minutes with full connectivity and would have sat there forever.

`onPlayerError` only raised a `StreamFailure` when the error `looksExpired()` (403/410), and
"Failed to connect" is not an expired lease — so nothing was raised and nothing retried.
`StallWatchdog` requires `isBuffering` and so did not fire either, correctly: the player was
in IDLE, not buffering.

`ExpiredStreamRecovery` is now `StreamRecovery` (the old name was a lie once it handled more
than expiry) and `StreamFailure` carries a `Reason`. The two reasons need **opposite**
responses, which is the point of naming them: `Expired` wants a fresh URL immediately;
`Unreachable` wants no request at all until `NetworkStatus.awaitOnline()` reports a validated
connection. A callback, not a poll — coming out of a tunnel, resuming on the instant rather
than up to an interval later is the whole experience.

Also fixed while testing it: the three-attempt budget was being spent in **56 milliseconds**
when the network failed fast, skipping the item. A retry with no gap is not a retry; attempts
2 and 3 now wait 2s and 4s.

### Files

- `app/…/playback/StallWatchdog.kt` — the sampler and the end-of-item decision
- `app/…/playback/StreamRecovery.kt` — expiry vs unreachable, the wait, the backoff
- `core/playback/…/StreamFailure.kt` — the reason the two are told apart
- `app/…/settings/NetworkStatus.kt` — `awaitOnline()`, on a callback

### Tests

`StreamRecoveryTest` (+4: an unreachable stream waits and then resumes from exactly where it
stopped; an expiry is never made to wait; unreachable still respects the budget; retries are
spaced out). `StallWatchdogTest` (11 cases, built on the report's real numbers): the reported stall
advances; 19s does not; a long stall advances exactly once; a mid-item stall is left alone; a
paused player is not a stall; buffering that keeps progressing is not a stall; a recovered
stall does not bank its time towards a later one; auto-play-off reports but does not play;
each item gets its own stall; an unknown duration is never the end; no state is not a stall.

## The rescue ladder, corrected after review (2026-08-19)

The ladder gained two rungs on 2026-08-18 (SABR, then sound-only) and an adversarial review of those
commits found the rungs themselves had introduced four faults. Recorded because each is a shape, not a
one-off:

| Fault | Shape |
|---|---|
| The rungs asked the **cursor** what was playing | the cursor is `-1` for a peek by design, so the whole ladder was dead for peeked items |
| The sound rung refused by **pillar** | a torrent is `PlayHandle.Podcast` and *has* a picture plus its own audio-only stream — the one item it could help |
| Both network rungs reset `attempts` | so the ladder re-walked from the top and `moveOn()` was unreachable — the queue re-fetched forever |
| The rescue cap sat **above** the disk rung | a download finishing mid-recovery was never consulted, which is the harm the disk rung exists for |

The order is now: **disk → cap → SABR → sound → move on**, all four rungs reading `playingNow`
(`_nowPlaying` falling back to the cursor), and the sound rung asking "does this item have a separate
soundtrack" rather than "which pillar is it".

Two supporting rules landed with it. A `Downloaded` row is now validated before the disk rung trusts it
— nothing revalidated one, so a copy the person deleted made `routeNow` prefer a missing file, which
Media3 reports as an `IOException` and recovery therefore retries forever behind a green tick. And a
content type that cannot be media is classed **permanent**, or the download is retried on every launch.

## Three faults that arrived disguised as an ending (2026-08-20)

The ladder above can only run on something that **fails**. Three SABR faults were reaching it as
success, and one as the wrong kind of failure. Full write-ups in
[streaming-reliability.md](streaming-reliability.md); what belongs here is which distinction each one
lost.

| Disguise | Read as | Actually | Now |
|---|---|---|---|
| a read gave up waiting for a byte that never came | `C.RESULT_END_OF_INPUT` — the video finished | a stall; ExoPlayer advanced the queue and the ladder never ran | `lastReadStalled` raises a fault on that read, without marking the shared stream spent |
| a rewind to byte 0 on a warm cached stream | every byte past the reader → a stall → the ending above | the request was never re-aimed | `aimAtByte` treats byte 0 as a jump, and the buffered ranges are lowered with it |
| a request that never landed | an empty answer, worth a thirty-second skip of the claim | we never asked; the media time was fine | the transport throws with the status and the error body; the claim is untouched |

The shape all three share: **two situations produced one line and one reaction**, and the cheaper
reaction was the one that looked like normal operation. A stall and an ending are both "no bytes"; a
dropped connection and "you already have enough for that time" are both an empty response body. This
repo's rule that a report must be able to answer the obvious next question is what these cost — none of
the three left anything in a diagnostics report to distinguish them by.

One ordering is recorded rather than fixed. `recoveryReasonFrom` makes a SABR stall outrank an
unreachable network, and both match whenever SABR gives up during an outage — a refused connection fails
in under a millisecond, so a read spends its six-fetch budget before the network can come back.
`Rejected` means one attempt and no `awaitNetwork`, so walking into a tunnel ends a SABR item where an
ordinary HTTP stream would have waited. Kept on purpose — an address YouTube refuses to serve wants a
fresh resolve, not a wait — and now pinned by `SabrStallOutranksTheNetworkTest` so it cannot be reversed
unnoticed.
