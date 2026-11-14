---
title: The learned fresh-URL window aims at the earliest acceptance, so most plays spend 4–6 young 403s
kind: todo
status: fixed 2026-11-14 from the emulator measurements below (offline test); phone effect on start time not yet seen
area: playback
priority: medium
requested: 2026-10-14
updated: 2026-11-14
---

# The learned fresh-URL window aims too early

Found chasing `edon5wb5Qsc`, which the soak reported as giving up in every build.

## What was measured (totum-api35, 2026-10-14)

- Alone, `edon5wb5Qsc` passed 2 of 2; after `jNQXAC9IVRw`, 3 passes failed once (both videos: 12 and 11
  young 403s, a fatal 403 then a give-up) and then 12 of 12 passed over six more passes. The give-up is
  real but rare and was not caught with logs (the soak tool now keeps every play's lines, `1444fb2f`).
- **Almost every play paid 4–6 young 403s**, e.g. `403 on a stream issued 686ms ago (YouTube accepts a
  new URL ~1599ms after issue, learned: 1599ms (refused up to 3008ms, accepted from 1599ms))`: retries at
  ~1.6 s, ~2.5 s and ~4.5 s, both tracks, the first two refused.

## Why

`YoungUrlWindow.validAfterMs` takes `minOf(latest refusal + 300 ms, earliest acceptance)` (commit
`11ff31a8`, ADR 13). One early acceptance (1.6 s) outranks refusals seen at 3 s for six hours, so the
retry is aimed where YouTube usually still refuses. The design assumed a stable window; on the emulator
it varies by URL.

## Not changed, and why

`11ff31a8` was measured on the phone, where the gain was faster starts; the emulator saw no young 403s at
all that day, so it never tested this case. Aiming at the refusal bound would cut wasted requests here and
might slow starts there. Decide from a phone report: `playback.youngUrlWindow` and the young-403 lines say
how often the phone retries early.

## Done when

A phone report shows how many young 403s a start pays; then either keep the minimum or aim at a
percentile of acceptances above the latest refusal, with `TheYoungUrlWindowIsLearnedTest` updated first.

## Fixed (2026-11-14)

`YoungUrlWindow.validAfterMs` now ignores acceptances earlier than the latest refusal when capping the
wait: they prove the window varies, not that it is open. With the emulator's numbers (accepted 1599 ms,
refused up to 3008 ms) the wait is 3308 ms instead of 1599 ms, which removes the retries at ~1.6 s and
~2.5 s that the measurement showed were refused. An acceptance at or above every refusal still caps it
(`min(refusal + 300, acceptance)`); refusals with no acceptance at all still wait at least the 4.8 s
default; an acceptance alone behaves as before. Tests: `TheYoungUrlWindowIsLearnedTest`, two new cases,
seen red (`expected 3308 but was 1599`, `expected 3200 but was 1599`). ADR 13 amended.

Still open and unmeasured: whether the phone starts slower. The trade-off is the one named above (fewer
wasted requests to YouTube against up to ~1.7 s later first retry on a URL that would have accepted at
1.6 s). `playback.youngUrlWindow` and the young-403 lines in the next phone report decide whether to revisit.
