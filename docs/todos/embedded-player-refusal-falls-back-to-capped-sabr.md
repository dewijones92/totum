---
title: When the embedded player refuses, SABR falls back to a client that cannot serve
kind: todo
status: evidenced, cause understood, fix is a decision for Dewi
area: playback
updated: 2026-09-20
---

# The fallback is worse than the thing it falls back from

Two CI runs, 47 minutes apart, on the same video (`uSMGENDH_QI`, NASA's "Cosmic Dawn"):

```
15:22:41  [resolve] uSMGENDH_QI resolved as the EMBEDDED player — the SABR endpoint that is not capped
16:09:58  [resolve] uSMGENDH_QI: the embedded player refused: ERROR: This video is unavailable; falling back to ANDROID
```

In the later run **every** SABR session in the whole suite came from the ANDROID player — eighteen of
them, zero embedded. And ANDROID's SABR is the capped one
([[youtube-android-client-first-megabyte]]): it served the init segment plus two more, 104,401 bytes,
and then answered every subsequent request with the same three runs for ever:

```
fetch #1 itag 137 at 0ms   -> 289187B response, 104401B kept
fetch #2 itag 137 at 429ms -> 289189B response, 0B kept   (retry 1 of 3)
fetch #3 itag 137 at 429ms -> 289189B response, 0B kept   (retry 2 of 3)
```

`AnHourLongItemDoesNotRebufferTest.anHourLongVideoPlaysOnWithoutRebuffering` fails on it, having
rendered nothing.

## What this is NOT

**It is not the warm-stream replay.** That was the explanation given in `5dc593b`, and this run
disproves it: the stream here opened **cold** — `opened at 0 of 1411564633 bytes (open #1)`, with
`dropped 1 held stream(s) for uSMGENDH_QI so a replay opens cold` logged moments before — and
produced byte-for-byte the same failure. The replay change fired exactly as designed and made no
difference to this. It is kept because continuing a previous play's conversation is still wrong, but
it has no evidence behind it and this file exists partly to say so.

It is also not a regression from anything shipped today: the embedded player served this video an
hour earlier and refused it later, which is YouTube's decision, not the app's.

## The actual problem

Falling back from EMBEDDED to ANDROID **for SABR** trades a working route for one that cannot serve
past its first hundred kilobytes. Failing the resolve outright would be better than succeeding into
a stall, because the ladder below has rungs that work — the log shows the app reached SABR only as a
rescue (`trying SABR as a rescue — the ordinary streams were refused`), and below it sits the
audio-only rung that has never been refused.

## The decision, which is Dewi's

When the embedded player refuses, should the app:

1. **skip SABR entirely** and go straight to the audio-only rescue (sound, no picture, immediately);
2. **try ANDROID SABR anyway** and fall through to audio when it stalls (a picture when the cap does
   not bite, at the cost of some seconds of nothing); or
3. **keep today's behaviour** and treat the stall as the ordinary stream-failure path?

Option 1 is the honest reading of the evidence. Option 2 is worth it only if ANDROID SABR ever
serves a long video, which nothing here shows. Not chosen unilaterally because it changes what a
shipped rescue does.

## Whatever is chosen

`AnHourLongItemDoesNotRebufferTest` currently asserts something the app cannot deliver when YouTube
refuses the embedded player, so it is a monitor of YouTube rather than a guard on this app
([[never-assert-someone-elses-policy]]). It needs to either assert the *fallback* behaviour, or say
out loud that the embedded refusal is an environment condition — and the second option is the one
that turned a real breakage into five green days in August, so prefer the first.
