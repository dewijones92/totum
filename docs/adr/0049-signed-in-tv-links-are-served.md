---
title: Signed-in TV links are served when the request is SmartTube's
kind: adr
status: accepted
updated: 2026-10-15
---

# 49. Signed-in TV links are served when the request is SmartTube's

- Status: Accepted
- Date: 2026-10-15

## Context

On 2026-10-14 YouTube began answering "Sign in to confirm you're not a bot" to every anonymous client from
Dewi's home IP: 9 of 10 soak videos were refused by yt-dlp 2026.07.04 and by the 2026.10.10 nightly (new
`tv_samsung` and `visionos` clients included). The signed-in TV fallback (`playerDowngradedTv`) still got every
format, but since about 2026-10-08 every one of its links answered 403. WEB-minted PO tokens (`pot=`, three
bindings, SmartTube's one-generator order) did not change that, and nor did tokens on the web clients.

SmartTube 32.56 on `totum-api35`, signed out, failed the same way. Signed in, it played the blocked video at
1080p. Its capture (`tools/emulator/mitm-capture.sh`) showed a TVHTML5 `/player` with no token, and plain GETs
of its links answered 206.

## Decision

`playerDowngradedTv` sends what SmartTube sends, as measured by changing one field at a time from the laptop:

- **The web-scale signature timestamp** (20738), not the TV scale (20738001). The TV scale still answers OK with
  every format, but every link it hands out is refused with 403.
- **SmartTube's Cobalt agent**, `Mozilla/5.0 (DirectFB; Linux x86_64) Cobalt/4.13031-qa (unlike Gecko)
  Starboard/1`. With the old `Cobalt/Version` agent the web-scale timestamp is answered "The page needs to be
  reloaded", which is why the TV scale was adopted in September.
- **`isInlinePlaybackNoAd: true`**. Without it some videos (2 of 4 tried) still get refused links.

Client version, visitor data, locale, the other context fields and the Referer made no difference. No PO token is
needed. `playerTracking` keeps the TV scale: it is history sync, it hands out no stream links, and it works.

## Consequences

- With the change, 11 of 11 blocked videos served their links from the laptop. On the emulator, two blocked
  videos that were not downloaded streamed at 1080p, with first sound in 1.4 s and 1.9 s, through the existing
  route: yt-dlp fails, and then "as downgraded TV -> N fetchable format(s)".
- Ciphered links (`signatureCipher`): 1 of the 11 soak videos, and every music-label track on Dewi's phone
  (0.1.617: "OK but nothing fetchable"). The bridge now solves signatures as well as `n` (`solve_sig`, the same
  V8/QuickJS providers), and `withSolvedChallenges` signs those formats before the `n` solve for both player paths.
  On the emulator: Gangnam Style 23/23 signatures in 0.98 s, a music track 20/20 in 72 ms, first sound 0.7 s.
- The signed-in path no longer falls back to the tracking client (`playerAsAccount`): its one plain link is 360p
  and answers 403 (`SignedInTvPlayerTest`).
- Test: `PlayerTimestampScaleTest` pins the timestamp, the flag and the agent. It failed against the old request.
