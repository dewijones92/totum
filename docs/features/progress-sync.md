---
title: Two-way progress sync with YouTube
kind: feature
status: outbound HELD (durable outbox; sender refused by YouTube since ~2026-08-18) · inbound works for resume AND rows
area: video
updated: 2026-09-06
---

# Two-way progress sync with YouTube

> ⚠️ **Status correction, 2026-09-06.** This page said `shipped` while the outbound half had been
> dead for nearly three weeks. Every report since ~2026-08-18 shows `carried no playback tracking;
> progress won't sync` and `-> NoSession`, and it was reproduced live on a signed-in emulator with
> YouTube's own history read back both from the app and from a signed-in browser: nothing arrives.
> The cause and every route probed are in
> [`../todos/outbound-progress-sync-is-dead.md`](../todos/outbound-progress-sync-is-dead.md).
>
> **Since 2026-09-06 nothing is lost any more.** Playback records every due update into a durable
> **outbox** (`AccountProgressOutbox`, Room v20) and a drain sends whatever it holds whenever a
> sender works — on every new record, at app start, and when the network comes back. What cannot be
> sent is kept, and the report says so: `yt-sync.outbound = Unavailable(reason, held)` and
> `yt-sync.pendingUpdates`. So listening on a plane is credited the moment a route works again, and
> a dead sender can never again look like a working one. The inbound half now reaches **rows** too
> (below). The rest of this page describes how the outbound half worked when it worked.

Dewi, 2026-07-25: *"confident that play progress is 2-way synced with YouTube servers or???"*. The
answer then was **no — one-way, and even that unverified**. Both halves are now true and measured.

## The outbound half was real, and is now proven rather than assumed

The spec said this had never been confirmed against YouTube itself, only that we send the right
request shape to a MockWebServer. Confirmed 2026-08-16, on Dewi's own account:

```
app log        [yt-sync] caVJh4jrOxE pos=789.873 -> Success
YouTube        FEhistory → caVJh4jrOxE, top of history, percentDurationWatched: 13
arithmetic     789 / 6253 (1:44:13) = 12.6%  ✓
```

So the pings do credit the account. That check was the prerequisite for building anything inbound —
there was no point reading a position back from a store we were not actually writing to.

## The inbound half, and the one constraint that shapes it

`FEhistory` returns, per tile, a `thumbnailOverlayResumePlaybackRenderer` carrying
**`percentDurationWatched`** — a whole number. Not a position.

That precision is the whole design. On a 1:44:13 video one percent is **62 seconds**, so a remote
position is good to about half a minute, while the local one is exact to the millisecond. Three
consequences, all deliberate:

- **The percentage becomes a position in the parser**, not later. The tile carrying the percentage
  carries the duration beside it, so that is the one place both numbers exist. `AccountProgress`
  carries the duration onward because the position's precision depends on it.
- **`resumeFrom` (`:core:domain`, pure) decides.** Local wins unless the remote is ahead by more
  than one percent of the duration, floored at 60s — one percent being exactly the resolution of the
  number being compared. Blindly preferring YouTube would make resume *worse* on the device you
  actually watch on: our own ping is what put that number there, rounded down on the way, so the
  remote is always slightly behind locally and would throw you back every time.
- **It is a decorator on the store**, not a second lookup. `Media3PlaybackController` asks one thing
  where an item resumes and that stays true. Podcasts fall straight through — YouTube has no opinion
  about them.

## Verified on device

Against Dewi's account, both directions:

```
# the device that did the watching keeps its exact position
resume caVJh4jrOxE at 1699621ms — LOCAL_IS_AS_GOOD [local=1699621 youtube=1688310 of 6253000]

# and a video watched ELSEWHERE, never opened here, resumes where he left it
resume 62HSUsS0ypo at 1654200ms — ONLY_REMOTE [local=none youtube=1654200 of 2757000]
playback  ready after 4507ms at 1656007ms      → started at 27:34
```

The log carries the inputs, not just the outcome, so a surprising resume can be re-judged from a
report without anyone guessing which side won.

## Resuming never waits on the account for long — and never offline (2026-09-06)

Report 0.1.477 (30 Aug), Dewi's note *"why the next video not playing??"*. The queue advanced, routed
to the downloaded audio, logged `play … from file:` — and then **nothing**, for 78 seconds, through six
taps. Every earlier play in the same offline session had waited 7s on `could not read watched
positions: Unable to resolve host` before its transition; the last six never transitioned at all,
because the read hung. `play()` asks for the resume position before it touches the player, and this
seam asked YouTube first, unbounded — so with no network the next item was held hostage to DNS.

Now `AccountResumePositions` is bounded (`REMOTE_WAIT_MS`, 1.5s — a healthy `FEhistory` answers in
~300ms) and **skipped when offline**. A read that does not make the cut keeps loading in the
application scope for the next play and for the rows; the resume line says which happened:
`(offline, so the account was not asked)` or `(the account did not answer within 1500ms …)`.
Guarded by `AccountResumePositionsTest."a hanging account read never holds up resuming"`, proven to
fail with the bound removed.

## Rows show the account's position too (2026-09-06)

Report 0.1.477 (22 Aug): *"Sutton video is actually half way through (playing it on YouTube website)
totum did not reflect this????"*. It did not, because the account's position was only consulted at
the moment of resuming a tap; every list drew its progress bars from the phone alone.

`AppContainer.rowPlayStates` now merges this device's `PlaybackProgressStore` with the account's
`FEhistory` map (`AccountResumePositions.watched`, refreshed on the same five-minute window while any
list is showing) through **one rule** — `accountAwarePlayState` in `:core:domain`, which maps the
position `resumeFrom` would choose onto a `PlayState`. Same judgement as resuming, so the bar and
the tap can never disagree. A local *Played* is final (exact and deliberate); a remote 100% is
*Played*; otherwise the further position wins by `resumeFrom`'s own one-percent rule.

## The outbox, in one picture

```
play (online or not) ─► WatchHistorySync records {id, pos, len, finished, at}   (latest per item)
                                   │ kick
                                   ▼
                        ProgressOutboxDrain ── sender works ──► sent, row removed
                                   └── refused / offline / signed out ──► kept; status says why + how many
   kicked again: app start · network offline→online edge · every new record
```

## Cost

One request per five minutes, not per play: `FEhistory` answers for every recent video at once, so
asking per tap would put a round trip in front of every play for a number that barely moves. Every
failure is an empty map — resuming from what the device knows is always safe, and an item resuming
locally is a far smaller problem than a screen that will not open.

## Files

- `core/domain/…/ResumeChoice.kt` — `resumeFrom`, the rule and its reasons
- `lib/innertube/…/VideoTileParser.kt` — `watchedPositions`, percent × the tile's own duration
- `lib/innertube/…/history/HttpYouTubeWatchHistory.kt` — the `FEhistory` read
- `app/…/video/AccountResumePositions.kt` — the seam, the five-minute cache, and the live `watched` map
- `core/domain/…/AccountProgressOutbox.kt` — the outbox port and `PendingAccountProgress`
- `core/domain/…/AccountAwarePlayState.kt` — the row rule, built on `resumeFrom`
- `core/database/…/RoomAccountProgressOutbox.kt` — Room v20 (`account_progress_outbox`)
- `app/…/video/ProgressOutboxDrain.kt` — the only thing that talks to the account; `OutboundSyncStatus`
- `app/…/video/WatchHistorySync.kt` — decides WHEN; records and kicks, never sends

## Tests

- `ResumeChoiceTest` — 10 cases: only-local, only-remote, remote ahead, remote behind, a lead inside
  one percent, a short item's floor, an unknown duration
- `AccountResumePositionsTest` — the fall-through for anything YouTube never saw, the cache, and a
  failed inbound read still resuming locally
- `VideoTileParserTest` — the positions read out of a **real captured history response** from Dewi's
  account, including the 13%-of-1:44:13 tile the outbound proof came from

- `ProgressOutboxDrainTest` — held when the sender is down, sent the moment it works (as *finished*
  where it was), a failed session re-attempted next drain, a working one opened once, the status a
  report needs, and a kick mid-drain not lost
- `WatchHistorySyncTest` — unchanged assertions, now read through the outbox + drain as wired in the app
- `AccountAwarePlayStateTest` — the Sutton case and its neighbours
- `RoomAccountProgressOutboxTest` (instrumented) — latest-per-item, and a send never deleting a
  record written while it was in flight

**Not covered:** that YouTube itself honours what we send. That was verified by hand (above) and
cannot be a test — it is an assertion about someone else's server.
