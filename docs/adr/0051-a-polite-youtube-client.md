---
title: A polite YouTube client
kind: adr
status: accepted
updated: 2026-11-07
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
Waiters are served in arrival order within each class (a `WaitLine` of tickets). The limit: a play that joins
a lookup already running in the background (a look-ahead) waits at that lookup's priority, because the calls
are made by the look-ahead's coroutine.
A superseded lookup is not cancelled: others can be joined to it, so cancelling would hand them a failure.

## Consequences

- A full subscriptions refresh now takes minutes, not seconds. The Videos feed fills in as it goes.
- An interactive call can wait behind a background trickle; a burst of 10 and 4 slots keep that short.
  The meter and the once-a-minute wait summaries will show whether it bites.
- During a 429 pause every YouTube call waits, which is the point. The banner says so and when it ends.
- The numbers (4, 2/s, 10, 30 s, 60 min) are judgements, not measurements of YouTube's thresholds.

## Update 2026-10-23: three lanes

Report 0.1.686 (Dewi: *"the last bunch of ops took a while"*): an album page's per-track like lookups (27 `next`
calls) filled the gate, and the artist page opened next waited 23 s behind them; 85 calls waited 724 s in all in that
report. The gate now has three lanes, served in order: **interactive** (a play someone is waiting for), **normal**
(everything not marked, page loads included) and **background** (`PoliteGate.background()`: the look-ahead resolves,
the queue's next-item readying, album years, album-page ♡ lookups, new-upload checks). Background calls may hold at
most half the slots (2 of 4), so two are always free for what you are waiting on. Tests in `PoliteGateTest`.

## Update 2026-11-07: the Pi is a polite client too

The Pi's `totum serve` process (ADRs 152, 156) calls YouTube on its own: the resolver behind a target or a home
download, the owner's handed-over sign-in (watch history and progress reports), yt-dlp extraction and downloads, and the
speaker's live HLS lookup. Only the last had a gate (one `PoliteGate` and `BotBackOff` added on 2026-11-07); the rest
went straight out, so the Pi could burst or ignore a 429 or a bot check that the phone had seen, from the same IP.

- **One `Politeness` per process, wired the way the phone wires it.** `PoliteInterceptor`, `PoliteYtDlpEngine` and
  `Politeness` moved from `:app` to `:core:video` (same package), and `Politeness.gated(engine)` and
  `Politeness.gated(clientBuilder)` are the only two ways either side attaches the gate, so the phone and the Pi cannot
  wire it differently. `main` makes one `Politeness`; the CLI commands use it, and `serve` hands the same instance to
  `HomeDownloads`, `YtDlpLiveHls` and `/health`. `HomeDownloads` and `YtDlpLiveHls` take the gate as a required
  parameter, so a path that forgets it does not compile.
- **What passes through it on the Pi:** every OkHttp call to `youtube.com`, `*.youtube.com`, `youtubei.googleapis.com`
  and `youtube-nocookie.com` (InnerTube, the signed-in TV player, the embedded player, watch history and progress
  reports, the watch-page sources), yt-dlp extract, search, channel, download and solve calls, and the live HLS lookup.
  The resolver's `InnerTubePlayerStreams` gets the same `BotBackOff` as yt-dlp, so a bot check from any of them stops
  the next anonymous yt-dlp call, wherever it comes from.
- **`PoliteYtDlpEngine` gains two gates on both sides.** A **download** holds a permit from before yt-dlp starts until
  the first bytes arrive (extraction is the YouTube call; the transfer after it is bytes and keeps the downloads' own
  cap of 4), and reports a bot check or a 429 it meets as extraction does. **Solving** (`n` and signatures) takes a
  permit too: this file's rule has always listed "extraction and solving", but the wrapper only gated extract, search
  and channel, and on the Pi each solve is a separate Python process given a youtube.com player-script address (not checked whether each one fetches it or reads yt-dlp's cache). Both apply to the
  phone as well, because it is the same class; neither is a change to its numbers (4 in flight, 2/s, three lanes).
- **Seen on `/health`.** `youTube` carries `inFlight`, `maxInFlight`, `queued`, `backedOff`, `slowDownUntilMs` with its
  reason, and `botBackOffUntilMs`. The Pi's own judgement (ADR 113) adds a home problem while either lasts, worded like
  the phone's banner: "🐢 YouTube says slow down" (warning), "🤖 YouTube bot check" (warning when the Pi is signed in:
  playing as its account), "🤖 YouTube is blocking this network" (broken when it is not). The phone shows them with the
  other home problems. A test pins the titles to the phone's string resources.
- **Still outside the gate, as on the phone:** player and video bytes (`googlevideo.com`: the relayed ranges, the live
  segment HEADs, `StreamStatus`), images, Google's OAuth token endpoint (`oauth2.googleapis.com`, not a YouTube host),
  and SponsorBlock. A download's byte transfer is held by the downloads' cap, not the gate.
- **Not done:** one gate across the phone and the Pi. They are two processes on one IP; each keeps its own 4 in flight, so
  the household can reach 8. Sharing the state would mean the Pi telling the phone (or the reverse) about a 429, which
  is a decision for Dewi.
