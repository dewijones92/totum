---
title: Two-way progress sync with YouTube
kind: feature
status: both halves shipped; YouTube's 10% floor no longer read as a position (2026-09-23, report 0.1.514)
area: video
updated: 2026-09-23
---

# Two-way progress sync with YouTube

> 🐛 **YouTube's 10% is a floor, not a position. Fixed 2026-09-23.** Report 0.1.514, Dewi: *"why
> downloaded files have position of like 5% in to the video? please fix?"*. Five videos played for a
> few seconds each came back from `FEhistory` at exactly 10%. `resumeFrom` let each one win and
> `AccountResumePositions` adopted it, so reopening jumped a tenth of the way in and the row drew a
> 10% bar. Across all 17 distinct remote
> positions in every report on the Pi, 12 are exactly 10% and **none is below it**. So
> `percentDurationWatched` bottoms out at 10, and a 10 means only "started".
> `resumeFrom` now answers `REMOTE_ONLY_SAYS_STARTED` for anything at or under 10% and keeps this
> device's figure. A floor already adopted before the fix is recognised by matching the recorded
> figure to the millisecond, which only adoption produces, and is treated as no position. The floor
> is never recorded as acted on. It is not our own echo: all five read `youtube=none` on
> their first play (13:34–13:40), so the 10 was each one's first reading ever, with only a few seconds
> of `cmt` sent before it and nothing adopted. The cost: a genuine 10% watched elsewhere starts from the beginning here, because
> it can't be told apart from six seconds. The 77700-of-777000 in 0.1.496 below was this same floor.

> ✅ **Outbound is back, 2026-09-06 (later the same day).** The sender was refused because the TV
> `/player` call declared the signature timestamp on the web scale (`20697`) where YouTube now wants
> the TV scale (`20697001`) — see [`../todos/tv-client-player-is-refused.md`](../todos/tv-client-player-is-refused.md).
> With `SignatureTimestamp.tv` on every TV player call, the outbox drained its held rows on the first
> launch (`outbound sync working — sent 4, 0 held`) and the four videos appeared at the top of the
> account's history in a signed-in browser. The report line that proves it in the wild is
> `yt-sync.outbound = Working(sent=N)` with `yt-sync.pendingUpdates` at 0 after being online.
>
> ⚠️ **Status correction, 2026-09-06 (earlier).** This page said `shipped` while the outbound half had been
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

> 🐛 **Two faults from report 0.1.496 (2026-09-20), and they were feeding each other — both fixed.**
>
> **Outbound was not refused; it was BLOCKED.** The status said
> `Unavailable(… the signed-in TV /player is refused, held=123)` and that reading was wrong: the
> same report holds five `tracking acquired for the account` lines. Three videos YouTube gives this
> app no tracking for sat at the head of the outbox, `MAX_FAILURES_PER_PASS` was 3, and one video's
> `NoSession` stopped the whole pass — so the drain asked the same three, thirty-seven times,
> and never reached row four. See
> [`../todos/outbound-progress-sync-is-dead.md`](../todos/outbound-progress-sync-is-dead.md).
> Now a per-video verdict is a `SessionResult.NotTrackable` — read from `playabilityStatus`, since
> a refusal is HTTP 200 and `ERROR` ("this video is gone") means something different from
> `UNPLAYABLE` ("the page needs to be reloaded", which is what a **stale signature timestamp**
> looks like for every video at once). The pass carries on past a per-video verdict, and
> **every failure sinks its row** — `pending()` orders by attempts before age, so whichever rows
> are failing, the next pass tries the others first. Nothing is ever dropped, and that is a
> measurement rather than a preference: the obvious fix was to write a row off after a few tries,
> and probing those same three ids against the live account a fortnight later found **all three
> now answer with tracking**. Their refusal was temporary. A write-off would have destroyed real
> listening to solve a queueing problem that ordering solves for nothing.
>
> That is also **why the rewind bug bit**: with nothing draining, YouTube's figure for the item
> could never move, and a figure that cannot move out-ranked every rewind.

> 🐛 **A frozen remote position made rewinding impossible — fixed 2026-09-20.** Report 0.1.496,
> Dewi: *"I have tried to rewind the video back to the start but it is not working"*. He rewound
> `vceHVwxOnhA` six times in two minutes and every re-entry answered
> `resume … 77700ms — REMOTE_IS_AHEAD [local=11273 youtube=77700 of 777000]` — **the same 77700
> every time**, because the outbound half was refused again (`held=123`) and so the account's
> figure could not move. The rule was comparing VALUES when the question is whether the remote
> has anything NEW to say. See [Ahead has to mean new](#ahead-has-to-mean-new) below.

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
  number being compared. At or under YouTube's own 10% floor it is not a position at all. Blindly preferring YouTube would make resume *worse* on the device you
  actually watch on: our own ping is what put that number there, rounded down on the way, so the
  remote is always slightly behind locally and would throw you back every time. **And it has to
  have MOVED** — a figure already acted on is old news; see below.
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

## Ahead has to mean new

Three faults stacked into one symptom in report 0.1.496, and **any one of them left alone still
breaks the rewind** — which is why all three are fixed together.

| Fault | What it did | Fix |
|---|---|---|
| The remote could not move | Outbound refused, so YouTube stayed at 77700ms and out-ranked every rewind for ever | A remote figure already acted on is **old news** |
| The rewind was discarded | `RoomPlaybackProgressStore`'s five-second floor dropped saves at 3790ms, 2595ms and 1144ms, so `local` stayed 11273 | The floor does not apply to a **chosen** position, nor to one that moves an existing position back |
| The rewind was never written | Progress saved only on a pause or every tenth tick, so rewinding and tapping another row four seconds later lost it; several resumes read `local=none` | A seek saves at once, as `deliberate` |

**`ReconciledAccountProgress`** (`:core:domain`, Room table `account_progress_reconciled`, v21)
records the account figure each time one is *used*. `resumeFrom` takes it as
`remoteAlreadyUsedMs`, and when the remote equals it the answer is `REMOTE_IS_OLD_NEWS` and local
wins whatever the two values are. A figure that has genuinely **moved** still wins, so watching
forty minutes on the TV is untouched — that is the whole feature and it is guarded by its own test.

It is a table rather than a column on `playback_progress` because that row is upserted wholesale on
every save, so a column there would be wiped by the next tick. It is durable rather than in-memory
because a cold start would otherwise hand the frozen figure its veto straight back.

Rows get the same third input through `AppContainer.rowPlayStates`, so a progress bar and a tap
still cannot disagree.

### A finished item starts again, whatever the account says

`accountAwarePlayState` has always said "a local Played is final — exact and deliberate, a rounded
percent cannot un-play it" about ROWS. The tap could not say it, because `resumePositionMs` answers
null for a finished item *and* for one never played, so a video finished on this phone still jumped
to YouTube's stale figure while the row beside it showed the Played tick.

The store now hands back the whole answer (`playState`) rather than a position, so the tap applies
the same rule. **The trade, stated:** finish something here, then watch forty minutes of it on the
TV, and the phone starts from the beginning rather than offering the forty. Two surfaces disagreeing
about one item is the bug class this seam exists to prevent, so agreeing on a rule you can explain
beats disagreeing on a better one — and "played here" includes reaching the end normally, not only
marking it by hand. Marking it unplayed is the escape hatch, and it works: a finished item records
nothing, so the account's figure gets a fresh hearing rather than a pre-set veto.

### What a stuck row costs now that nothing is dropped

Keeping every row for ever is only safe if it cannot become perpetual traffic, so a row that has
failed more than a few times is tried on one pass in ten (`STUBBORN_AFTER`, `RETRY_STUBBORN_EVERY`),
a pass is capped at twenty rows and a drain at three passes — the kick arrives every fifteen seconds
of playback, so bounding the pass without bounding the re-entry would have bounded nothing.

And the question the design now raises — *is anything permanently stuck, and which?* — is answered
in the report rather than by reading code, which is what 0.1.496 took: `yt-sync.stuck` names the
worst few by id and attempt count.

### "Acted on" has to be true, not assumed

Recording the figure at the moment the *answer is computed* was wrong in a way an adversarial pass
caught before it shipped. Watch forty minutes on the TV, tap the item here, and close the app two
seconds later — before any tick, pause or seek can save. The figure is recorded as used while this
device still holds its own ten minutes, so the next tap answers ten minutes and **can never offer
the forty again**, because a dead outbound sync means the number will never move. Thirty minutes of
progress, silently lost. A double-tap produced the same thing, since the second tap saw what the
first had recorded.

So when the account's figure wins it is **adopted into this device's own store** on the way past,
as a `deliberate` save. The two then agree, and the answer is the same whether or not anything is
played afterwards.

The same pass moved the old-news check **ahead** of the local-is-null case. Having no position for
an item already resumed once means the position was taken away — marked unplayed, or played to the
end — and an echo of the old decision must not put it back.

### Known limitation

`account_progress_reconciled` is keyed on the item alone, so figures recorded against one YouTube
account are compared against another's after a sign-out and sign-in. It takes a numeric coincidence
to matter, and Dewi uses one account, so it is documented rather than fixed. The table also grows
one small row per video ever resumed and is never pruned; `yt-sync.reconciled` in every report is
the gauge that will say when that stops being true.

**What a report now shows**, because a change has to be provable in the wild from its logs alone:

| Line | Answers |
|---|---|
| `resume <id> at Nms — <why> [local=… youtube=… of … alreadyUsed=…]` | which side won, and on what |
| `youtube=Nms for <id> is now acted on (was …) and adopted as this device's position` | when a figure stopped being news, and whether it was adopted |
| `saving <id> at Nms — the position was chosen` | that a seek reached the store |
| `not saving <id> at Nms — under the 5000ms floor` | that one did not, and why |
| `yt-sync.reconciled` / `yt-sync.reconciledInQueue` | what the table holds NOW, per queued item — not only at the instant of a play |

The last two are the ones whose absence hid this for two months: a dropped save and a stale figure
were both completely silent.

## Files

- `core/domain/…/ResumeChoice.kt` — `resumeFrom`, the rule and its reasons
- `core/domain/…/ReconciledAccountProgress.kt` — the account figures already acted on
- `core/database/…/RoomReconciledAccountProgress.kt` — Room v21 (`account_progress_reconciled`)
- `core/database/…/RoomPlaybackProgressStore.kt` — the floor, and the rewind it must not swallow
- `core/playback/…/Media3PlaybackController.kt` — `onPositionDiscontinuity`, which saves every seek
- `lib/innertube/…/VideoTileParser.kt` — `watchedPositions`, percent × the tile's own duration
- `lib/innertube/…/history/HttpYouTubeWatchHistory.kt` — the `FEhistory` read
- `app/…/video/AccountResumePositions.kt` — the seam, the five-minute cache, and the live `watched` map
- `core/domain/…/AccountProgressOutbox.kt` — the outbox port and `PendingAccountProgress`
- `core/domain/…/AccountAwarePlayState.kt` — the row rule, built on `resumeFrom`
- `core/database/…/RoomAccountProgressOutbox.kt` — Room v20 (`account_progress_outbox`)
- `app/…/video/ProgressOutboxDrain.kt` — the only thing that talks to the account; `OutboundSyncStatus`,
  and the rule that one video's verdict is not the sender's
- `app/…/video/WatchHistorySync.kt` — decides WHEN; records and kicks, never sends

## Tests

- `ResumeChoiceTest` — 22 cases, among them: only-local, only-remote, remote ahead, remote behind, a lead inside
  one percent, a short item's floor, an unknown duration, and the four for old news (a figure already
  acted on losing, the same numbers without it still winning, a moved figure still winning, and
  nothing local to protect). Proven to fail with the rule removed: `expected:<0> but was:<77700>`.
  Plus the 10% floor (2026-09-23): not a position with nothing here or a small position here, 11% still
  a position, 0.1.496's own figure being the floor, an adopted floor being no position, this device's
  own tenth being kept, and an adopted floor giving way to real progress. The old-news cases moved to 27% so they still
  reach the rule they test; at 10% they would pass without it.
- `AccountResumePositionsTest` — the fall-through for anything YouTube never saw, the cache, a
  failed inbound read still resuming locally, and the three for 0.1.496: a rewind surviving a figure
  that cannot move, progress made elsewhere still overruling one, and what was acted on outliving
  the instance that used it; and 0.1.514 end to end, the floor neither moving a six-second video nor
  overwriting its local position (red with the floor check disabled)
- `RoomPlaybackProgressStoreTest` (instrumented) — a rewind below the floor moving an existing
  position, a trivial position still creating nothing where there is none, and a short replay not
  un-playing a played item. Proven red: `expected:<1144> but was:<11273>`
- `RoomReconciledAccountProgressTest` (instrumented) — remembered, latest-per-item, unknown is null
- `RewindIsRememberedTest` (instrumented) — the real player and the real store: a seek is written
  down well inside the five-second tick, and a rewind to the start replaces a position the floor
  used to protect. Proven red with the seek save removed
- `VideoTileParserTest` — the positions read out of a **real captured history response** from Dewi's
  account, including the 13%-of-1:44:13 tile the outbound proof came from

- `ProgressOutboxDrainTest` — held when the sender is down, sent the moment it works (as *finished*
  where it was), a failed session re-attempted next drain, a working one opened once, the status a
  report needs, a kick mid-drain not lost, and eight for 0.1.496: an untrackable video not blocking
  what is behind it, a failing row sinking rather than being dropped, one that starts working again
  still being sent, nothing lost when every row refuses, enough failures to end one pass not ending
  the next, a sender failure sinking its row too, a newer position keeping the attempts already
  made, and a pass staying bounded on a large backlog. Five of them proven red against the old
  drain, including `expected:<[bad1, bad2, bad3]> but was:<[bad1, bad2, bad3, good1, good2]>`
- `OneUntrackableVideoDoesNotBlockTheOutboxTest` (instrumented, live, manual) — the same thing
  against the REAL account: a dud ahead of a real row no longer stops the real row reaching YouTube
- `WatchHistorySyncTest` — unchanged assertions, now read through the outbox + drain as wired in the app
- `AccountAwarePlayStateTest` — the Sutton case and its neighbours
- `RoomAccountProgressOutboxTest` (instrumented) — latest-per-item, and a send never deleting a
  record written while it was in flight

**Not covered:** that YouTube itself honours what we send. That was verified by hand (above) and
cannot be a test — it is an assertion about someone else's server.
