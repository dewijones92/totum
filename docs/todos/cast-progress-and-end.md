---
title: Casting keeps your place and notices the end
kind: todo
status: built; tests written
batch: A (2026-11)
priority: high
updated: 2026-11-14
---

# Casting keeps your place and notices the end

Reports 20261101T174143 ("finished on the TV, app says 70%") and 20261101T205710 (TV off, app showed Peep Show):
- `Media3PlaybackController.saveProgress` returns on a blank receiver mediaId (~1008), so nothing is saved while casting;
- `RemoteEnd.seen` is seeded only from a named item, so the TV's end is missed (no mark played, no advance);
- `PlaybackService.switchTo` caches `lastHandoffItem` only at cast start (~477-485), so the phone takes back the item
  from the start of the session;
- `GaplessArmer.due` arms at once on cast (~55);
- a spurious "ended at 0ms of -1ms" auto-advanced over a tap (cause before the buffer).
Fix each with a test seen red first; use the `CastLoadHold.unnamed()` stand-in, mindful it names what Totum last asked
for. No test covers `RemoteEnd` yet.

## Built 2026-11-06 (code first; tests are the testing stretch's)

- Save under the stand-in: `Media3PlaybackController.saveProgress` / `namedId` / `standInFor` (nothing saved or counted
  when the receiver holds two items and names neither).
- The end: `RemoteEndWatch` (pure; a Pi target feeds `observe`/`left` from its own status), `RemoteEnd` (the Media3
  adapter, now seeded from the stand-in), one `announceEnd` (marks played, emits once, drops a duplicate within 3 s).
- The hand-back: `CastHandoff` in `PlaybackService`, fed by `onSetMediaItems` / `onAddMediaItems`, the TV's transitions
  and a 5 s position tick.
- Arming: `GaplessArmer.due` is the 75 s window on a cast too (playing is enough, the receiver reports no buffer).
- Spurious end: `SpuriousEnd` (unknown length, at most 1 s in, within 3 s of a start).
- Also: `TimeMappedCastPlayer` reports the source's duration (position was already source time), otherwise a cut item
  looked near its end early and the percentage was skewed.

Tests the testing stretch must add *(written in part 2026-11-14: see "Tests written 2026-11-14" below for what is done and what is still owed)*, each seen red on the old code:
1. `RemoteEndWatch`: an item seeded from the stand-in whose receiver then drops it near its end gives `Ended`; the old
   `RemoteEnd` never seeded it. Also: dropped early gives nothing; a still-present item gives nothing; `finishedOnTv`.
2. `saveProgress` with a blank receiver id saves under the stand-in (and under nothing with two items): needs the
   decision extracted or a controller test with a fake `MediaController`.
3. `CastHandoff`: after `started(A)`, `started(B, 43%)`, `progressed`, the hand-back is B at its place (old code: A at
   its start); `crossed` promotes the armed item; `itemFor` prefers a named receiver item.
4. `GaplessArmerTest`: on a cast, not due at 10 minutes left, due inside 75 s while playing, not when paused.
5. `SpuriousEnd`: unknown length at 0 ms 500 ms after a start is ignored; at 0 ms 10 s later, or with a known length, is
   not. And `announceEnd` counts one end for the same item twice within 3 s.
6. `TimeMappedCastPlayer`/`CastTimeline`: duration is the source's when the timeline cuts, the receiver's otherwise.

Unverified on a device: whether adding an item to the receiver's queue restarts the playing one (report 20261101T170416
suggested it); the 75 s window makes it rarer, not impossible.

## Tests written 2026-11-14

`RemoteEndWatchTest`, `RemoteEndTest` (the Media3 adapter over a proxy `Player`), `CastHandoffTest`, `SpuriousEndTest`, `GaplessArmerOnACastTest`. Owed: `saveProgress`/`standInFor` (private to the Media3 controller, need a `MediaController`), `announceEnd` dropping a duplicate within 3 s, `TimeMappedCastPlayer`'s source duration, and a real TV.
