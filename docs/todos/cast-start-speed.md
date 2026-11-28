---
title: A cast through the Pi starts fast and carries your place
kind: todo
status: built; tests written; not yet on the real Pi
batch: C (2026-11)
priority: high
updated: 2026-11-28
---

# A cast through the Pi starts fast and carries your place

Report 20261120T211551 and the Pi's logs of 20 to 26 November. Decision and evidence: [ADR 189](../adr/0189-an-adapter-owns-transport-only-policy-is-shared-and-pinned-by-the-contract-suite.md).

- Built: warm Python workers on the Pi, one engine contract suite, warm-up at start, the look-up beside the TV connection, the
  phone's resolve handed over, the start place carried both ways, one log line per start.
- Open: confirm on the Pi that `resolver was built in N ms` and the `warm` lines read as expected; measure a real cast after the
  deploy (the per-start line has every stage); a persistent node solver would remove the roughly 210 ms of a laptop solve (several
  seconds on the Pi) that remains per solve; the 5 s before the first YouTube call is explained only as first-use cost.
- Not mine: the TV connect overlapping a waking device (27 s and 51 s links on 21 and 23 Nov), the relay cache lock, captions and the dock bar.

## Measuring it on the Pi (after the deploy)

`docker logs -t totum-fetch`, then read these lines in order for one cast:

| Line (tag) | What it tells you |
|---|---|
| `engine: worker solver started (start #1, pid …, Nms to spawn …)` and `worker general started …` | the two workers came up at start; `start #2` or later means one was replaced (a `retired` warning above it says why) |
| `warm: dewidebug the engine is warm after Nms` | the bridge imported yt-dlp and built the solver |
| `warm: dewidebug player <url>: ok=true alreadyHeld=… took=Nms` | the one-off cold parse of the current player build, now paid at start (the old 21 s) |
| `home: dewidebug that resolver was built in Nms` | the cast resolver, built at start; the old "5 s before the first YouTube call" |
| `engine: worker solver: solve_n answered in Nms (start #S, …)` | the solve a cast waits on: the first after start is warm, so it should be the same as the second, a few hundred ms on the Pi at most |
| `handover: dewidebug N handed resolve(s) from <phone>: K kept, Nms` and `…the phone's resolve is kept` / `…answered 403 here` | whether the phone's resolve saved the Pi a solve |
| `targets: <id>: looking up <item> while the link is still being made (waited Nms since the request was accepted)` | the look-up started beside the TV connection |
| `targets: <id>: start of item N done … accepted=…ms-before-the-link link=… sinkWait=… resolve=… resolveWaitedAfterLink=… play=… total=… (resolve overlapped the link by Nms)` | every stage of one start; `resolveWaitedAfterLink` near 0 and "overlapped" means the resolve cost nothing extra; compare `total` with the 14 to 38 s of 20 to 26 Nov |
| `targets: <id>: playing … from=Nms (the place it was asked for / the account's place / the start …)` | where the cast began, and why |
| `targets: <label>: <id> starts from Nms, the place this phone would have resumed at` (phone log) | what the phone sent as the carry-on place |

A first cast after a Pi restart and a second cast a minute later should have the same `solve_n` time. If the first is
slower, the player warm-up did not run or named a different build: compare the `warm` line's `<url>` with the one the cast
solved for.
