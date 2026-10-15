---
title: A polite YouTube client
kind: adr
status: accepted
updated: 2026-10-15
---

# 51. A polite YouTube client

- Status: Accepted
- Date: 2026-10-15

## Context

YouTube bot-blocked the home IP on 2026-10-14. Nobody knows what triggered it: the same IP carries
Totum, the Pi, SmartTube on the Fire Stick and the laptop. But Totum had nothing stopping bursts. Group
feeds fanned out with no limit, channel refresh fetched 6 feeds at a time across 1,609 channels, there
was no 429 handling outside subtitles, and each bot-refused play made 6–8 refused calls. Dewi asked
for back-off on 429, 4 requests at most in flight, and a notice in the app. He then approved five more
ideas: fewer requests, a rate limit as well as a concurrency limit, spread background bursts, a request
meter, and one consistent visitor id.

## Decision

One `PoliteGate` for every YouTube API call (4 in flight, 2/s, bursts of 10, a 429 back-off), at the two
places all of those calls pass: the OkHttp interceptor and the yt-dlp engine wrapper. Gating at the
transport means no feature has to remember to. A `BotBackOff` for anonymous yt-dlp, a banner, a meter
in diagnostics, look-ahead paused while throttled, and the visitor id kept per install. Details in
[polite-youtube-client.md](../features/polite-youtube-client.md); the rule is in CLAUDE.md.

## Update, 2026-10-15: evidence, and fewer calls per play

Report 0.1.625 (Pixel 4a) showed 102 calls waiting 755 s in one minute and a play taking 22 s, with nothing
saying what held the gate. Now:
- a call kept 2 s for a slot logs at once which calls hold all four, and for how long (`polite.slowSlots`,
  at most one line per 5 s, the rest counted);
- `VideoResolver` keeps every lookup in flight by URL, so tapping A, B, A again joins A's lookup instead of
  starting another (it held only one);
- the signed-in player asks the watch page for a title only when the caller's row has none
  (`MediaItem.titleIsKnown`): for music it never has one, and it cost a call and a 150 KB log line per play;
- a resolved item whose only title is its id keeps the row's title (`withStreamFrom`).

## Update, 2026-10-15: a tap goes first

A play (a tap, play now, an automatic advance) resolves inside `PoliteGate.interactive()`, a thread-local
coroutine element, so it reaches the OkHttp interceptor as well. While an interactive call waits for a slot,
background calls (look-aheads, prefetches, sync, pages) do not take a free one. Still at most four in flight.
A superseded lookup is not cancelled: others can be joined to it, so cancelling would hand them a failure.

## Consequences

- A full subscriptions refresh now takes minutes, not seconds. The Videos feed fills in as it goes.
- An interactive call can wait behind a background trickle; a burst of 10 and 4 slots keep that short.
  The meter and the once-a-minute wait summaries will show whether it bites.
- During a 429 pause every YouTube call waits, which is the point. The banner says so and when it ends.
- The numbers (4, 2/s, 10, 30 s, 60 min) are judgements, not measurements of YouTube's thresholds.
