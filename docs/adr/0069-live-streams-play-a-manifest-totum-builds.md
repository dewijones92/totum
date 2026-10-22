---
title: Live streams play a manifest Totum builds from the live head
kind: adr
status: accepted
updated: 2026-10-22
---

# 69. Live streams play a manifest Totum builds from the live head

- Status: Accepted
- Date: 2026-10-22

## Context

Live YouTube did not play on the phone. Found 2026-10-22 while starting on live casting: a 24/7 stream played
about 5 s, jumped to 0 and started over, for ever, and was recorded as finished on the account. The signed-in TV
player answer for a live stream carries no manifest (`streamingData` holds only `adaptiveFormats` and
`expiresInSeconds`), and each live format's URL (`live=1`) serves one segment per `&sq=<n>`; the ladder handed the
player one such URL as if it were a file. Anonymous clients that would return an HLS manifest are bot-blocked on the
home broadband (ADR 49).

SmartTube, the TV client the signed-in path copies, solves exactly this (read in `yuliskov/MediaServiceCore` and
`SmartTube` on 2026-10-22): one GET of the live audio URL, whose headers `X-Head-Seqnum` and `X-Head-Time-Millis` give
the newest segment and the stream's length, then a DASH manifest of the adaptive formats with `&sq=$Number$` segments.

## Decision

- `LiveDash` (`:lib:innertube`, pure) reads the head from the response headers and builds a static DASH manifest
  starting three segments behind the head: one adaptation set per container (MP4 and WebM apart, or the WebM
  segments reach the MP4 extractor), the best audio, segment length `X-Head-Time-Millis` / `X-Head-Seqnum`. Each live
  segment is self-contained (`ftyp moov emsg moof mdat`), so no init segment is needed.
- `LiveStreamManifest` (app) applies it to a signed-in answer that is live (`isLiveContent` **and** formats with
  `live=1`: `isLiveContent` is also true for the recording of a past stream); the manifest is served on loopback by
  `LocalManifests` (`127.0.0.1:8766`, IPv4: `getLoopbackAddress()` gave `::1` on the emulator) and plays as the one
  "🔴 Live" quality. A YouTube HLS manifest, when an answer carries one, is used the same way.
- A resolution that finds an item live makes it `MediaContentKind.LIVE` (both merges, `kindLearnedFrom`), and a live
  item **starts at the edge, never at a saved place**: a saved 179 s applied to a fresh manifest asked for future
  segments and failed with 404s.
- `ChunkedDataSource` passes a live segment (`sq=`) straight through: with a `Range` header googlevideo sends the
  segment and then holds the connection until the timeout (15 s from the laptop, ~8 s in the app), which stalled
  every segment; a plain GET takes 0.1–0.3 s.

## Consequences

- On the emulator a 24/7 stream plays at real time with one 1.7 s start and no stalls over minutes (it stalled 32 s of
  75 before the pass-through).
- The manifest is built once per resolve; a resolve replayed from the cache much later starts further behind the edge
  (still inside the stream's DVR window: segments 23 behind the head answered).
- **Casting live**: the TV cannot fetch googlevideo (CORS), so the cast server serves the same manifest with each
  segment URL pointing at the phone (`LiveRelay`), which fetches it with a plain GET and passes it on. The TV picks
  its own quality from the manifest, so a live item skips the replay for the TV's formats (that cost a 5 s wait and
  a second load). On the Ultra: cast tap to playing in 5.6 s, the TV's clock at real time, stop hands back to the
  phone still live. The live sound is relayed as it is: Totum's boost does not reach a live stream on the TV yet.
- Not yet: Totum's processing (boost) on live audio while casting, DVR seeking back, and anonymous yt-dlp answers for
  live (they come as HLS formats and are not mapped).
