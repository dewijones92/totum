---
title: Gapless queue — the player holds the next item, and the queue adopts the crossover
kind: adr
status: accepted
updated: 2026-10-07
---

# 20. Gapless queue — the player holds the next item, and the queue adopts the crossover

- Status: Accepted
- Date: 2026-10-07
- Amends: [ADR 13](0013-playback-start-young-urls-held-bytes-and-copies.md) (the next-item preloader
  now serves only what gapless leaves alone)

## Context

Dewi, 2026-10-07, in a diagnostics note: *"I want the subsequent video/audio to load so there is no
wait in-between queue items"*. In that report the next item started 0.5 s and 1.3 s after the last
ended, with its first 30 s already preloaded. The time went on rebuilding the player per item:
`setMediaItem` + `prepare`, decoders restarted. Only the player itself crossing from one item to the
next avoids that.

He chose (plan page `20261007-2018-totum-gapless-queue-plan.html`): **true gapless**; **Wi-Fi only**
for streams, as the preloader always was (a downloaded next item needs no data, so it is gapless
everywhere); **no cue** between items; a **"Gapless queue" setting, on by default**, as a kill switch;
and the notification / headset **next** button may jump straight to the item already waiting.

Until now the code said the queue plays one item at a time because it owns advancing
(`NextItemPreloader`'s KDoc, and "a one-item timeline, which is all this app builds" in the
controller). The queue still owns advancing; what changes is how the next item is handed over.

## Decision

1. **Arm.** `GaplessArmer` watches the playing item. In its last 45 s it asks the queue to
   `armNext(allowStream = !metered)`. The queue routes the next entry **through the same `decide()`
   and launcher stream choice as a real advance** and, instead of playing it, calls
   `PlaybackController.armNext(...)`, which adds it as the player's second playlist entry. Not armed
   when the setting is off, auto-play next is off, or the sleep timer stops after this item
   (`gaplessNotNow`); taken back (`disarmNext`) when any of those becomes true or the queue's next
   item changes.
2. **Resume point.** An armed item carries its saved position in its request metadata
   (`EXTRA_START_MS`); the service wraps its source in `StartsAtSource`, whose timeline's default
   position is that point, so the player buffers and starts it there.
3. **Cross over.** When the player moves to the armed item by itself (AUTO transition, or a SEEK from
   the next button), the controller closes the outgoing item (ENDED and played when it finished; its
   position saved when skipped), switches every per-item fact at once (`ItemContext`: SponsorBlock
   segments, chapters, subtitles, listing facts) via `becameCurrent`, drops the finished entry, and
   emits `PlaybackEvent.CrossedOver` — deliberately not `Ended`, so nothing advances twice.
4. **Adopt.** The gapless wiring (`startGapless`, started from `TotumApplication`) answers `CrossedOver`
   with `PlaybackQueue.adoptCrossover`, which is the
   ordinary `playAt`: cursor, now-playing, fresh start (so `StreamRecovery` follows), launcher state,
   history and the watch session all update exactly as on any play. When that play reaches the
   controller for the item and stream the player is already on, `NextInLine.adopt` matches it and
   **nothing is rebuilt**; any mismatch (a different stream, a different item) rebuilds, which is
   today's behaviour. One path, no second copy of the per-item bookkeeping.
5. **A newer choice wins.** The queue counts plays and remembers which one an item was armed under; if
   anything was chosen after that (a tap in the instant after a crossover), the crossover is not adopted and
   that choice rebuilds the player as usual. Found by `AutoAdvanceLoopTest` replaying an item right after it
   crossed over: the late adoption cancelled the replay. The same holds after the adoption has begun: every
   route checks, once it has decided, that no newer play started meanwhile, and stands down if one did (the
   launcher already did this for streams; file and podcast routes did not). Without it a replay made 8 ms into
   an adoption lost to the adoption, whose route happened to take longer to decide.
6. **Arming follows the item it was meant for.** An arming that completes after the player has moved to
   another item is dropped; an adoption of the same item does not cancel it. The first version cancelled on
   any newer `play()`, so an item shorter than 45 s never put its successor in line (the queue soak caught it:
   streams lose that race, local files win it). The armer never arms the item that is already playing.
   It also waits until the playing item has **settled** — playing, not buffering, and at least 10 s (or the
   rest of the item) buffered ahead — so a short item is not asked to share its own start-up with the next
   item's lookup. The soak caught that: "Me at the zoo" (19 s) armed before its first sound and failed 2 of
   3 passes against 0 of 3 on the build before.
7. **Anything that rebuilds** (`play()` of another item, a rescue rung, a quality or Listen/Watch
   switch) drops what was armed. A player error while something is armed disarms it, so the failure
   is handled as before and the next item gets its own ordinary start.

## Consequences

- On Wi-Fi or for a downloaded next item, an auto-advance has no rebuild. Logged as
  `dewidebug [gapless]` lines (armed, not armed and why, disarmed and why, crossed, adopted / not
  adopted) and counted in `playback.gaplessHandovers`; `settings.gaplessQueue` is in every report.
- On mobile data, with the setting off, or with nothing armed, playback advances exactly as before.
- After a rescue rebuild of the playing item the next item is not re-armed for that item; it then
  advances the old way.
- The Cast handoff moves only the current item; an armed item is not carried to the cast device.
- Tests: `NextInLineTest`, `GaplessArmerTest`, `GaplessQueueTest` (incl. a newer choice winning), `AutoAdvancerTest`,
  and `GaplessCrossoverTest` on a real player (crossover with no `Ended`, the finished item played,
  the resume point honoured, the setting off falls back, short items chaining).
- Measured with `tools/emulator/soak.py queue` on `totum-api35`, real YouTube items, seeking to 40 s before
  each end: gapless off 4.6 / 3.7 / 4.4 s of silence at the three hand-overs, gapless on 0 / 0 / 0 s.
