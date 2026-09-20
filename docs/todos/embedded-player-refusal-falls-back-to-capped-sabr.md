---
title: The hour-long video stalls at 104401 bytes, under every client, cause unknown
kind: todo
status: observations only — TWO proposed mechanisms have been disproved; do not propose a third without a control
area: playback
updated: 2026-09-20
---

# What is actually observed

`AnHourLongItemDoesNotRebufferTest.anHourLongVideoPlaysOnWithoutRebuffering` (video case,
`uSMGENDH_QI`, SABR the **primary** route because the test sets `setSabrPlayback(true)`) has failed in
three consecutive CI runs. The signature is identical every time:

```
fetch #1 itag 137 at 0ms   -> 289187B response, 104401B kept
fetch #2 itag 137 at 429ms -> 289189B response, 0B kept   (retry 1 of 3)
fetch #3 itag 137 at 429ms -> 289189B response, 0B kept   (retry 2 of 3)
fetch #4 itag 137 at 429ms -> 289189B response, 0B kept   (retry 3 of 3)
itag 137 gave nothing at 30429ms but only 104401B of 1411564633B served — NOT ending, skipping ahead
```

The reader then waits at offset 104401 for the segment after the ones it has, while the stream holds
segments 1, 2, 6 and 7 around 3.1 MB. `STUCK: itag 137 has no bytes at offset 104401` repeats until
the test gives up.

# Two mechanisms proposed for this, both WRONG

Recorded because the pattern matters more than either wrong answer.

1. **"A replay reused a warm stream and rewound it."** Disproved by the next CI run: the stream opened
   COLD (`dropped 1 held stream(s) … so a replay opens cold`, then `opened at 0 … (open #1)`) and
   failed byte for byte the same.
2. **"The embedded player refused it, so SABR fell back to the capped ANDROID client."** Disproved by
   the runs already in hand, both of which had been read before the claim was written:
   - The run where the video **did** resolve as EMBEDDED failed this same test the same way
     (`15:23:43 failed: anHourLongVideoPlaysOnWithoutRebuffering`). It was never the healthy control
     the claim treated it as.
   - In the very run cited as the indictment of ANDROID, ANDROID served **11,315,189 bytes** on other
     items. "Cannot serve past its first hundred kilobytes" is false on its face.
   - The stall's held-segment structure is the same under both clients.

   So the client identity correlates with nothing here.

Also wrong in that version: it said the app "reached SABR only as a rescue". The test sets SABR as the
**primary** route, which matters because the audio-only rung is reachable from the rescue ladder and
not from the primary path — so "skip SABR and go straight to audio" was not implementable where it was
implied. And "the rung below has never been refused" was asserted with no evidence; the audio case was
SKIPPED in one of the runs quoted.

# The discipline this file exists to enforce

Three causal stories were written today from logs that were merely *consistent* with them. Every one
would have died in about a minute against the question **"is there a run where my proposed cause is
absent and the failure still happens?"** — and in every case that run was already downloaded.

So, before proposing a mechanism for this: find the control. State what varies and what does not.
A log line proves the branch that emits it ran, and nothing else.

# An open lead, offered as a lead

The app's own `skipping ahead` is a candidate: it fires after the fourth empty answer, lands the
conversation at ~3.1 MB, and the reader is still at 104401 waiting for the byte after what it holds.
Segments 3, 4 and 5 are never fetched. Whether the skip creates the gap or merely follows one is
**not established**, and the next person should establish it before changing anything.

# Whatever is chosen

The test asserts something the app currently cannot deliver on this route, so it is failing honestly
and should stay red rather than be softened — the alternative turned a real breakage into five green
days in August ([[never-assert-someone-elses-policy]] cuts the other way here: this failure is about
*our* code, not a third party's policy).
