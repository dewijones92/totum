---
title: Outbound progress sync is dead — no client will give a signed-in session tracking URLs
kind: todo
status: open — reproduced and root-caused 2026-09-06; every cheap route probed and eliminated
area: video
priority: critical
requested: 2026-09-06
updated: 2026-09-06
---

# Outbound progress sync is dead

Dewi, 2026-09-06: *"I especially want the play progress to be reflected back into the YouTube
servers under all circumstances, especially when listening to the audio file offline."*

It is reflected under **no** circumstances, and has not been since about 2026-08-18. Both reports
from 0.1.477 (22 Aug, 30 Aug) and a live replication today say the same thing.

## Reproduced, signed in, both witnesses

On `totum-api35`, signed in (the app read 14 recent positions from the account), online, current
build:

```
[share]   shared link -> https://www.youtube.com/watch?v=aqz-KE-bpKQ
[yt-sync] player signature timestamp is 20697
[yt-sync] aqz-KE-bpKQ carried no playback tracking; progress won't sync
[yt-sync] aqz-KE-bpKQ pos=0.0 fin=false -> NoSession
```

Two minutes of playback later, YouTube's history — read with the account's own token **from the
app** (`FEhistory`, 14 recent, `aqz-KE-bpKQ present=false`) and **from a signed-in Edge** on the
laptop (`/feed/history`, top item unrelated) — does not contain the video.

The offline case is worse still: report 0.1.477 (30 Aug) shows 16 minutes of offline listening end
`eYrMF9Cht8A pos=949.601 fin=true -> NoSession` — finished, and the account never told, with nothing
kept to tell it later.

## Why: the one client that takes our bearer is the one YouTube refuses

`HttpYouTubeWatchHistory.beginSession` gets its `videostatsPlaybackUrl` / `videostatsWatchtimeUrl`
from a **signed-in TV `/player` call**. Probed every client, both with and without the account
bearer, videoId `aqz-KE-bpKQ`, `signatureTimestamp=20697` (2026-09-06):

| client | + account bearer | anonymous |
|---|---|---|
| **TVHTML5** (what we use) | `UNPLAYABLE: The page needs to be reloaded` — 0 formats, **no tracking URLs** | `LOGIN_REQUIRED: confirm you're not a bot` |
| TVHTML5_SIMPLY | HTTP 400 | `UNPLAYABLE: confirm you're not a bot` |
| TVHTML5_SIMPLY_EMBEDDED | `ERROR: no longer supported` | `ERROR: no longer supported` |
| WEB | **HTTP 400** | OK, 40 formats, **tracking URLs present** |
| ANDROID | **HTTP 400** | OK, 35 formats, **tracking URLs present** |
| IOS | **HTTP 400** | OK, 13 formats, **tracking URLs present** |

Two independent walls, and they close every door between them:

1. The only clients that still hand back tracking URLs are WEB / ANDROID / IOS, and **only
   anonymously** — a TV OAuth bearer on any of those contexts is HTTP 400 (the documented
   "bearer on a WEB/mobile context = 400" rule, [[smarttube-as-youtube-reference]]).
2. The only client that *accepts* our bearer, TVHTML5, is **refused outright** — the same
   `The page needs to be reloaded` wall as the PO-token / attestation blocker, which yt-dlp's `tv`
   client hits too (`SignedInVersusAnonymousPlayerTest`, and `tv-client-player-is-refused.md`).

## The decisive test: anonymous URLs + a bearer do NOT credit the account

The obvious idea — take the anonymous ANDROID tracking URLs and ping them *with* the bearer — was
tried and **eliminated** today. Both pings returned **HTTP 204** (accepted), and 25s later the
video was absent from `FEhistory` and from the signed-in browser. A session minted anonymously is
attributed to nobody; adding a bearer to the stats ping does not adopt it. This confirms the code's
own 2026-07-31 note: anonymous tracking URLs return 204 and change nothing.

## Where that leaves it

There is **no cheap route** left. The send is blocked by the same thing that blocks SABR: YouTube
will not serve this app a signed-in player session. The remaining real avenue is the one already
named in [[po-token-minting.md]]: **SmartTube streams and tracks history from a TV on the same
network**, so capturing its actual `/player` + stats requests and diffing them field-by-field is
what would replace this with an answer — it is either using cookies rather than a bearer, a client
we have not tried, or a PO token attached to the player call.

## What is buildable NOW regardless of the wall

Two things, both worth doing whether or not the sender is ever fixed:

- **A persistent progress outbox.** Today the app pings live and drops the result, so offline
  listening is lost even for the day a sender works. Persist `{videoId, positionMs, lengthMs,
  finished, watchedAtMs}` per item; drain it whenever a sender succeeds; keep the row when it
  cannot. Then "listen to the whole thing on a plane" is credited the moment the network and a
  working sender return, and nothing is ever silently lost.
- **The truth in diagnostics.** `NoSession` is currently indistinguishable from "working". A report
  must say `outbound sync unavailable (TV /player refused) — N updates held` so this can never
  again look shipped for three weeks while dead. The status doc lied exactly that way
  ([progress-sync.md](../features/progress-sync.md) said `shipped`); corrected 2026-09-06.

The inbound half (resume position from `FEhistory`) is **unaffected and still works** — the app
read 14 positions from the account in the same run.

## Done when

- An outbox persists every play's progress and drains on the first successful send, proven by
  listening offline, killing the app, and seeing the update sent (or held with a truthful reason)
  on next launch.
- A diagnostics line states plainly whether outbound sync is available and how many updates are held.
- If the SmartTube capture yields a working session route, `beginSession` uses it and the outbox
  starts actually reaching the account.
