---
title: The "ask YouTube directly" fallback serves TV URLs that 403
status: fixed 2026-10-08 by checking before trusting — cause: YouTube refuses TV-client stream URLs without a PO token (reproduced; control served)
updated: 2026-10-08
---

# The "ask YouTube directly" fallback serves TV URLs that 403

When yt-dlp offers a single 360p stream, `VideoResolver.betterQualities` asks the signed-in TV client
for the ladder (`resolved as the signed-in TV client`, `solved 2/2 n parameter(s)`). On 0.1.575 that
produced 6 qualities to 1080p 19 times, and 15 of them ended in a fatal `HTTP 403 from client TVHTML5`
with ~6 h of lease left, still refused 5–7 s after issue (so not the fresh-URL window, ADR 13).

Untested hypotheses: the `n` is solved against the web player (`1b3be681`) rather than the TV client's
player; or TV URLs need something the app does not send. The path had not run in any earlier report,
so there is no baseline. ADR 11's 2026-10-07 amendments should keep it from being reached in the case
seen; when it is reached and fails, recovery falls back to a copy on disk or the sound.

## Investigated (2026-10-08)

Phone reports: the path ran 20, 8 and 3 times in three reports (0.1.575, 0.1.575, 0.1.577) and failed 15, 7 and 2;
it has not run since 0.1.581 (the ADR 11 amendments keep it from being reached in the usual case).

`WhyTvUrlsAreRefusedTest` (manual, signed-in `totum-api35`, NASA "Cosmic Dawn" `uSMGENDH_QI`, build `f2999a12`):

| Client | `n` solved by | User-Agents tried | 8 MB in | First 1 MB | +8 s |
|---|---|---|---|---|---|
| control: anonymous ANDROID | base.js / tv-player-ias.js | okhttp, Dalvik, Android YouTube, Cobalt TV | 206 | 206 | 206 |
| downgraded TV (1080p) | base.js / tv-player-ias.js | all four | 403 | 403 | 403 |
| TV (360p) | base.js / tv-player-ias.js | all four | 403 | 403 | 403 |

So it is not the solver's script, the User-Agent or the fresh-URL window: TV-client URLs (`c=TVHTML5`)
are refused outright. They carry no `pot=`; YouTube most likely wants a GVS proof-of-origin token for this
client now, which only attestation produces (see `youtube-requires-attestation.md`).

## Fix (Dewi's choice: check before trusting)

`VideoResolver.servedLadderOr` asks `StreamStatus` for the first kilobyte of the ladder's best rung before
swapping it in. Refused (not 2xx) → keep yt-dlp's stream and log `… YouTube answered HTTP 403 for its 1080p
stream (client TVHTML5) — keeping yt-dlp's 360p`, vital `resolve.playerFallbackRefused`; served or not
checkable → use the ladder as before (`[its 1080p stream answered HTTP 206]`). Covered by
`ARefusedDirectAskKeepsYtDlpsStreamTest` (red first: the old code never checked). The signed-in TV player
moved from `AppContainer` into `video/SignedInTvPlayer`.
