---
title: Live streams play a manifest Totum builds from the live head
kind: adr
status: accepted
updated: 2026-11-06
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

## Amended 2026-11-06: a live stream that has not started

Report 20261104T171409: tapping a live event before it began logged `UNPLAYABLE: This live event will begin in a few
moments` on the signed-in TV client and every yt-dlp client, and nothing showed. A refusal like that is a state, not a
failure, so it is now typed and waited for.

- `PlayerResult.Unplayable.upcoming` (`UpcomingLive`: scheduled start when given, YouTube's words) is read from the
  refused response; yt-dlp's refusal is recognised by its words. `UpcomingLives` (`:core:video`) holds them by video id
  and clears one when the video resolves; rows, the refused-tap toast and the waiter read it.
- `LiveStartWatcher` (app) turns a refused tap on an upcoming live into an operation on the progress strip (Cancel) and
  checks through `VideoResolver.liveCheck`, which asks only the signed-in TV client and the anonymous player (the two
  requests already gated by the polite client, ADR 51), never yt-dlp. `LiveWaitSchedule`: one check a minute before the
  scheduled time (at most 15 min apart), else every 2 min for 5 checks, every 5 min to the 15th, then every 10 min, up to
  6 h; a stream scheduled more than 6 h ahead is not waited for.
- When it is live, a "🔴 … is live" notification with Play opens Totum on the watch link (the shared-link way in); giving
  up posts "⌛ Stopped waiting for …".
- Not verified on a device: the exact shape of the TV client's refusal (the code accepts `LIVE_STREAM_OFFLINE`, an
  `offlineSlate`, or the wording, and logs the raw `playabilityStatus` as `dewidebug upcoming live`).

## Amended 2026-11-06: live plays at 1x, nothing cut

Report 20261104T171409 (0.1.852): two lives played at the listener's 2x with skip-silence, outran the live edge and
gave up buffering after 30 s and 24 s. A live stream has no future to run ahead into, so `MediaContentKind.LIVE` now
has the same `ItemTuning` as music (`MusicPlaysPlain`): speed 1x, no skip-silence, `keepsChanges = false`, so a speed or
skip-silence change made while it plays is for it alone and never replaces the saved talk choices (`setSpeed` already
honoured that guard). No silent-tail ending either (it was already off for live). The play line also names a loopback
manifest as "a live manifest Totum built" instead of "the torrent server", which it was mistaken for.

## Amended 2026-11-06: the tag follows the stream

`UpcomingLives` became `LiveStates` (upcoming, live, ended) and every surface reads the tag through one function; a stream
that has ended is `MediaContentKind.WAS_LIVE` and plays as an ordinary video. See [ADR 132](0132-the-live-tag-is-read-from-one-registry-and-kept-true.md).
