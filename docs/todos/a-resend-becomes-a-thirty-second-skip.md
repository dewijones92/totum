---
title: A resend is read as "the server has nothing" and answered with a 30-second skip
status: open
severity: critical
updated: 2026-08-20
---

# A resend is read as an empty answer, and the cure strands the player

Measured on `totum-api35`, 2026-08-20 17:38, `AnHourLongItemDoesNotRebufferTest` audio-only over a
five-minute soak. **rebuffers=1 at 59979ms.** The video arm of the same run rebuffered zero times, so
this is specific to the audio-only path, and it is deterministic rather than flaky: the byte count
below is the same one a report gave on 2026-08-19.

## The trace

```
fetch #6 itag 251 at 47872ms -> 346444B response, 160745B kept
  run 1 startBytes=657225 seq=5 length=161489
  run 2 startBytes=818714 seq=6 length=160745      -> held to 979459B
fetch #7 itag 251 at 57271ms -> 184734B response, 0B kept, carried 251=171364B
  run 0 init=true startBytes=0 length=10619
  run 1 startBytes=818714 seq=6 length=160745      -> a RESEND of seq 6
itag 251 got no bytes at 57271ms from 184734B
itag 251 gave nothing at 87271ms but only 979459B of 99276855B served
  — NOT ending, skipping ahead (empty #1)
```

## The mechanism, in three steps

1. The server re-sent segment 6, which we had already served. `storeMedia` drops it because
   `offset (818714) < served (979459)`, so `added == 0` — **even though 171364B of this format's
   own bytes arrived.**
2. `added == 0` routes to `handleEmpty`, whose remedy is to push the claimed position forward by
   thirty seconds: 57271ms becomes 87271ms.
3. The player is at about 58s. Our claim now says 87s, so we never ask for 58–87s again, and the
   only thing that can happen next is a stall.

The skip exists for a real case — a server with genuinely nothing for a position — but it cannot
tell that case from this one, and here it is the direct cause of the failure it is meant to avoid.

## Why the server resent it

979459B at 168kbps is about 46.6 seconds of audio, while the claim at that moment was 57271ms: the
claim was already some eleven seconds ahead of the bytes. Whatever the server made of our buffered
ranges, it answered with a segment we already had. Which of those two is the cause is not yet
established, and there is no way to tell from a report, because **we never log the ranges we send** —
`describeProgress` prints only `described=<count>`. That is the first thing to fix, before the
mechanism.

## What the fix has to distinguish

Three situations currently produce one line and one reaction:

- the server has nothing for this position (skip forward is right)
- the server sent bytes for this format that we already served (our ranges are not being honoured,
  or our claim is ahead of our bytes — skipping forward makes it strictly worse)
- the server sent bytes for the *other* track only (nothing about our position is wrong at all)

`carried` already knows which. A response that carried this itag's bytes must never be treated as an
empty answer.

## Related

- The straddling-run discard and the `writeAt`-before-discard claim inflation, both being fixed in
  the same pass, are the other two doors into this same runaway.
- `HeldSegments.asRanges` starting at `held.firstKey()` and never pruning is the likely reason the
  ranges stop describing the buffer truthfully once a gap exists.
