---
title: One fetch per item, a playing stream is its own download, and nothing swaps a healthy stream for a file
kind: adr
status: accepted
updated: 2026-11-28
---

# 186. One fetch per item: a playing stream is its own download, and nothing swaps a healthy stream for a file

- Status: Accepted
- Date: 2026-11-20 (amended 2026-11-27: the cast relay does not block on the cache; a video played as an HLS playlist cannot be teed, and the hold says why; amended 2026-11-28: that video is fetched beside its stream, and the hold starts when the play is decided)

## Context

Report `20261120T200136-b1afd577` (0.1.938), Dewi: *"why was I buffering a few seconds into the video? it played like
10 seconds and it buffered"*. The trail:

- `20:01:09.657 wf8YXNWhkvc finished downloading while streaming: switching to the downloaded audio … at 7904ms`, then
  `wants to play during its hand over to the download; the rescue will play it`, then
  `20:01:10.995 playback ready after 1296ms at 7913ms`. The same at 19:58:26 for `-pfEWYjniMM` (1286 ms).
- The stream was healthy (about 78 Mbps). The queue auto-download had fetched the same audio (3.6 MB, yt-dlp) beside the
  player's own fetch of it, and the finished file then replaced a stream that needed no replacing ([ADR 13](0013-playback-start-young-urls-held-bytes-and-copies.md) point 4).

Two defects, one cause. The same bytes crossed the network twice, and the second copy was swapped in mid-play at the cost
of a player rebuild. Dewi's rule, in his words: *"click an item, it streams to my ears AND to a file"*: one fetch, and
that is the default, because downloading the audio of the queue is already the app's default.

## Decision

1. **A healthy stream is never swapped for a file.** When the download of the playing item lands while it streams
   steadily (`wantsToPlay`, playing, not buffering), `PlaybackQueue.handOverToTheDownload` defers the switch and logs why
   (position, buffered-ahead). It then swaps at the first natural break, judged by `DeferredHandOver` on the controller's
   state: a **pause**, the stream **stalling** (buffering), or a **jump** of the position of 3 s or more (a seek, a skip).
   Starting the item again, another item playing, or the item ending drops the deferral (a start takes the copy through
   `routeNow` by itself). The swap logs its trigger and how far the position had moved since the download finished.
2. **While an item streams, nothing else fetches the same media.** `QueueAutoDownloader` takes `heldWhileStreaming`: the
   item that is playing from a stream whose bytes cannot become its copy. That item is left alone (one log line:
   *not fetching … while it streams … fetched once it pauses or stops, if still queued*) and is fetched when it pauses, ends or
   another item takes over, if it is still in the queue and not downloaded. This reverses the "playing item first" ordering
   of report 0.1.435 for a streaming item with no tee; once it is not streaming, it is first again.
3. **The stream is its own download (the tee).** Media3's playback cache ([ADR 72](0072-a-playback-cache-under-every-stream.md))
   already writes whatever the player reads. For a YouTube **audio** stream read through it, `PlaybackTee` remembers the
   stream (cache key `yt:<id>:<itag>:<clen>:<xtags>`, its latest signed address, its length) and
   `TeeDownloadStrategy` (wrapping the app's download strategy) makes the audio download **from those bytes**:
   - `StreamTeeFiller` fetches only the byte ranges the cache does not hold, through the same cache key, in 256 KB chunks,
     **from the far end backwards**, and never inside a margin of max(2 MB, 5 %) around the playhead while the player plays
     (`StreamTeePlan`). A paused, ended or absent player lets it fill everything. When only the margin is left it waits.
   - The cache's data source now blocks on a locked span (`FLAG_BLOCK_ON_CACHE`) instead of reading around it uncached,
     so a chunk being written by the tee never makes the player fetch the same bytes without storing them.
   - When the cache holds the whole stream it is copied out to the download's file and recorded as an audio-only copy.
     No yt-dlp, no second fetch, no player rebuild. The chunk fetch is interruptible, so cancelling the download frees it.
   - When it cannot (a video download was chosen, the stream is not a cacheable YouTube audio stream, the address has
     expired, or the copy fails), the download falls back to the ordinary strategy **after it says why** and, for the auto
     download, after the item stops streaming (point 2). The queue treats an item as teeable once its audio stream has opened
     through the cache (`PlaybackTee.teeableNow`), so it is not held then.
4. **The traffic is measured.** A counting listener sits on the cache's upstream and counts the network bytes of every
   stream by cache key (`PlaybackTee.networkBytesFor`). The tee's completion line says the file size, the bytes the tee
   fetched and the bytes the stream and tee fetched together as a multiple of the file (`dewidebug … x the file`), so a report
   can prove there was no duplicate fetch. Deferral, tee, fall-back and hold-off decisions are logged under `playback`, `tee`
   and `download`.

## Evidence

Instrumented, on `totum-api35`, a real ExoPlayer playing a 15.9 MB WAV through the real cache and tee over a counting
fake upstream (`AStreamIsFetchedOnceThoughItIsAlsoDownloadedTest`, 4 cases, all passing):

| case | file | network | refetched | tee fetched |
|---|---|---|---|---|
| keeps playing, fast connection | 15,876,044 | 15,876,044 (1.000x) | 0 | 262,144 |
| keeps playing, slow connection (the tee and player race) | 15,876,044 | 15,876,044 (1.000x) | 0 | 262,144 |
| player left after 4 s | 15,876,044 | 15,876,044 (1.000x) | 0 | 6,350,400 |
| player left, slow connection | 15,876,044 | 15,893,684 (1.001x) | 17,640 | 13,812,120 |

The 17,640 bytes are the unflushed tail of the cache sink when the player is released mid-read (its buffer is 20 KB).
The JVM model of the planner against a fake upstream that counts bytes per range (`StreamTeeFillerTest`) shows the same
for a 70 MB stream played from the start, resumed in the middle, and paused: nothing fetched twice.

Real ExoPlayer reads the whole file in one open-ended read (one range in the first two rows), which holds an unbounded span
lock; the tee's chunks then wait behind it (blocking, not uncached reads), so on a stream that is still loading the player does
the fetching and the tee adds almost nothing. That is the right outcome (one fetch), and the tee does the work when the player
has stopped or paused. It also means the download of a playing item completes when the player has loaded the file, not before.

## Consequences

- No double fetch of a playing YouTube item's audio, and no mid-play rebuild: the 1.3 s buffering of the report cannot occur
  for a healthy stream. A swap only follows a pause, a stall or a seek.
- The auto-download of the playing item now waits for the tee's evidence (the audio stream opening through the cache). For an
  item that streams but cannot be teed (podcast enclosures, whose cache key lives for one launch only; SABR and live streams,
  which bypass the cache), the download waits until the item pauses or stops. With the stream held, the rescue ladder has no
  disk copy for those items while they play (the premise of report 0.1.435); it still has the stream retries, the sound-only
  and SABR rungs.
- The tee's download shows as downloading until the player has loaded the whole file, then completes. The auto-downloader's
  lane waits at most `SETTLE_TIMEOUT_MS` (10 min) for it, logs that it gave up waiting, and moves on; the download carries on.
- `FLAG_BLOCK_ON_CACHE` makes the player wait at most one tee chunk (256 KB) when they meet. If a lock were ever stuck the
  player would wait; nothing in the tests shows one, and the tee's failures release their spans.
- `JsonDownloadStore.change` (shared by the app and the Pi) now writes the file first and publishes the new list only when the
  write succeeded; a failed write is logged and thrown to the caller, memory stays where the disk is.

## Not built, and why

- **A download made from the cache for a different format** (a video download, or an audio format other than the one the
  player plays) is not attempted: a tee must be the stream that is playing. These fall back to the ordinary strategy. A
  deliberate manual video download of the playing item still fetches its own bytes, as chosen; it is logged, not held back.
- **Podcast and other non-YouTube streams** are not teed (their cache keys are per launch and the length is not in the address),
  so by the consistency rule ([ADR 108](0108-consistent-everywhere-and-its-recorded-exceptions.md)) they follow point 2: not
  fetched twice while they stream, fetched at a pause or stop. Teeing them needs a stable key and a length from the response.
- **A gapless swap** (adding the file as a playlist entry at the same position, as in [ADR 20](0020-gapless-queue-the-player-holds-the-next-item.md))
  was not tried: the tee removes the reason to swap at all, and a healthy stream is left alone.
- **Unproven on the phone.** The instrumented evidence is a real player over a counting fake upstream; whether YouTube's real
  address keeps working for the tee's late chunks (it expires; the fall-back covers it) is for the next reports to say through the
  `tee` lines.

## Amended 2026-11-27: the cast relay never waits on a span another reader holds

Reports 20261123T123853, 124440 and 171635 (0.1.944): a phone-direct cast hung and the TV gave up after about 46 s (`receiver error 321`).
The relay lines said `first byte in 28816ms`, then `57253ms`, then `90030ms`, all released when the session ended. Cause: point 3 above put
`FLAG_BLOCK_ON_CACHE` on the one cache data source, and the cast relay read upstream through the same cache (`CachedUpstream`, `CastStreams`).
A deep range at the resume position (`bytes=131437596-…`) opened a span that the phone's own player loaders, the tee filler or an earlier
relay request held, and `SimpleCache.startReadWrite` waits on that lock with no timeout. v0.1.942 had no flag and no hang. Red first:
`ARelayReadWaitsForNoLockedSpanTest` (instrumented, on `totum-api35`) held a span and asked the relay for it; against the old code it
failed with "the relay is still blocked on the locked span after 9002ms".

- **Two data sources, one cache.** `PlaybackCache.factory` (the player, and the tee's filler) keeps `FLAG_BLOCK_ON_CACHE`: the no-double-fetch
  guarantee above is for the PLAYER path and is unchanged. `PlaybackCache.relayFactory` builds the relay's source without it, so a locked span
  is read straight from upstream (uncached, as before the flag) and the relay never installs the tee's fill source.
- **A budget as the second line of defence.** `CachedUpstream` opens through `BudgetedOpen`: if the cache has given no answer in 6 s and
  upstream was never asked (a lock, not a slow origin), the attempt is abandoned and upstream is read directly; the abandoned open is closed
  when it finally returns. A slow origin is waited for, not asked twice.
- **The trade-off.** While another reader holds a span, the relay may fetch the same bytes the holder is fetching, so a cast during playback of
  the same YouTube item can briefly fetch twice. A hung cast is worse than a duplicate fetch, and the relay's reads are not stored then.
- **Logged per request** (`cast`, `dewidebug relay open #N`): the open wait split into time in the cache and time to upstream's first byte
  (an `OpenTimes` rides in the `DataSpec`'s custom data through the cache to a timing wrapper around the upstream), the bytes the cache
  holds from that position, and whether it was read through the cache or around it. The first five opens and any over 1 s are logged one by
  one, over 3 s as a warning, the rest counted in a 30 s summary line. Who holds a locked span is **not known**: `SimpleCache` does not say,
  so the warning lists the usual suspects instead of naming one.


## Amended 2026-11-27: a video played as an HLS playlist cannot be teed, and the hold says why

The nightly of 27 Nov 2026 (run 46076397579, v0.1.970, commit b2dd1b6d) failed only `SeveralSharedLinksAllLandTest.everySharedLinkIsQueuedAndFetchedExactlyOnce`
("not everything shared was fetched for offline listening", after 304 s). Three links were shared. Two (`jNQXAC9IVRw`, `aqz-KE-bpKQ`) were queued and not playing,
so the downloader fetched them with yt-dlp (`[tee] … downloading it on its own: no audio stream of it is playing through the cache`). The third, `ttiLcMUQq80`, was the
last share and started playing as a **video**: `route ttiLcMUQq80 -> streaming the video … [handle=Video … listen=false]`,
`stream 1080p · AVC avc1.640028 … (one stream)`, then `play ttiLcMUQq80 from https://manifest.googlevideo.com/api/manifest/hls_playlist/… [route=hls]`.
The downloader said `not fetching "Moonbound Episode 2 | For All Humanity" while it streams` and `pass skips: … 1 held while it streams`. It played for 285 s with nobody
pausing it, so the hold never lifted and the test timed out.

**Which case it was: not a defect in the tee; the test was out of date with this ADR.** `PlaybackTee.teeableNow` was false because the item never opened an audio
stream through the cache. An HLS playlist and its segments are `direct, not cached (live, manifest, loopback or not http)` in `PlaybackCacheKeys.cacheable`
(`.m3u8`, `/manifest/`, `sq=`), and the log shows only that line for the playing video (22:48:35.905 onwards, one per segment). The segments are not the audio file
(itag 140) that a download is, and none of them is stored, so there are no cached bytes to complete and copy out. The tee covers one thing by design (point 3):
a YouTube **audio** stream read through the cache (`mime=audio`, cache key `yt:<id>:<itag>:<clen>:<xtags>`), which is what a merged video's separate audio
track would be too (not exercised by this run: this video was one HLS stream).
A video that plays as one stream (HLS, SABR, a muxed format) has no such track, and joins the podcast enclosures and live streams under "an item that streams but
cannot be teed": its download waits until it pauses, ends or another item takes over. That is what the hold did.

- **The test now pauses playback once everything is queued**, then expects all three fetched exactly once. Nothing was weakened: still three links, still the
  same fetch assertion and the same exactly-once check on the `[download] start` trail. Sharing plays the last link as a video, and a video that never pauses is
  by this ADR never fetched.
- **The hold line now says why the item is not teeable** (`TeeVerdict`, from the route the player was handed, `PlayRoutes.of`, and whether an audio stream of it
  opened through the cache): `dewidebug not fetching "<title>" while it streams: … [why it is not teeable: it plays as an HLS playlist (…) [route=hls]]`.
  The routes it names are HLS, SABR, a live manifest, the torrent server, a local file, no recorded route, and "a direct url with no audio stream opened through
  the cache" (a muxed stream, a podcast enclosure, or an audio stream not yet opened). A report can now settle this without reading the cache lines.
- **Not changed on 27 Nov, and a question for Dewi (answered 2026-11-28, see the amendment below):** a video watched through HLS gets no offline audio copy until it pauses or stops, and the audio it would fetch
  (35 MB beside 435 MB of video for this item) is about 8% of what the stream carries. Fetching it beside the stream would break "one fetch" for that 8%.
  The ADR kept the hold; relaxing it for video streams was a decision for Dewi, not built then.


## Amended 2026-11-28: a YouTube video over HLS is fetched beside its stream, and the hold starts when the play is decided

Dewi, 2026-11-28, on the question the 27 Nov amendment left open: he accepts a second fetch of the audio, about 8% of the bytes (35 MB beside 435 MB of video
in the nightly's case), for **a YouTube video streaming over an HLS playlist (`route=hls`)**, so that its offline copy lands while he watches instead of
after. It is the one exception to "one fetch" and it is deliberate.

- **Only that case.** `StreamHold.of(streaming, teeable, route)` (`:core:playback`) is the one rule: an item that can be teed is neither held nor
  fetched beside (its download comes from the stream's bytes); `route=hls` is **fetched beside its stream** (`StreamHold.Beside`, with its reason);
  everything else that streams is **held** until it pauses, ends or another item takes over (`StreamHold.Hold`): **podcast enclosures (`a direct url`),
  SABR, live manifests, the torrent server** and an item whose route is not recorded yet. `route=hls` is read from the address handed to the player
  (`hls_playlist`, YouTube's manifest path), so it is a YouTube video in practice; no other source produces it today.
- **What the fetch is.** The ordinary strategy: `TeeDownloadStrategy` finds nothing to tee (`no audio stream of it is playing through the cache`) and the
  audio-only download runs with yt-dlp, from the front of the pass (the playing item goes first). The hand-over rules of point 1 are unchanged: a
  finished copy never replaces a healthy stream, and an audio-only copy does not stand in for a video being watched.
- **The log line** (`download`): `dewidebug fetching "<title>" beside its stream, on purpose: it plays as a YouTube HLS playlist (video and audio cut into
  segments the cache never stores), so there is no audio stream to copy; its audio is fetched beside the stream, a small fraction of what the video carries
  [route=hls]`, once per play. The hold line for the cases that are still held is unchanged and still carries `TeeVerdict`'s reason.
- **The race, which was real.** The 27 Nov run showed `ttiLcMUQq80`'s download starting at 23:15:34.740 and `PLAY wanted` at 23:15:35.379. The pass
  puts the playing item first (`playingNow`, set when the play starts), but the hold read `controller.state` (`wantsToPlay` for that item), which is true only
  after `launcher.play` has resolved the video and handed it to the player, several seconds for a YouTube video. In between the item was neither held nor
  teeable, so the downloader fetched it with yt-dlp and the player then fetched the same bytes: a double fetch nobody chose, for every kind of item that
  streams (an audio stream that WOULD have been teed, a podcast that would have been held). For a video over HLS it was harmless by luck (that case is now
  fetched beside anyway); for the rest it was the duplicate point 2 exists to prevent. `StreamingIntent` (`:app`) closes it:
  - the queue says an item is about to stream **when its route is decided** (`PlaybackQueue.route`, before the resolve), forgets the item's route from an
    earlier play (`PlayRoutes.forget`, so a replay is not judged by last time's route) and logs `<id> is about to stream; the auto-download is told now`;
  - the item counts as streaming until the call that plays it returns, and from then on the controller's `wantsToPlay` decides, as before;
  - a lift (a pause, the end) is told after 1.5 s, so the moment between the call returning and the player saying it plays cannot flicker the hold off
    and let a pass fetch the item; a route that never reached the player (refused, superseded, failed) is released the same way once its call returns;
  - the downloader re-evaluates when a route is recorded (`PlayRoutes.changes`), so the hold turns into "fetch beside" (HLS) or stays a hold (SABR, a
    podcast) the moment the player is handed the address, and into "tee" when the audio opens through the cache.
- **Tests (seen failing first):** `StreamHoldTest` (5; `Hold(id=vid)` where `Beside` was expected), `PlayRoutesTest` (3 new; `expected null, but was hls`,
  `expected:<1> but was:<0>`), `StreamingIntentTest` (6; `the downloader must be told before the player has the item ... expected:<[null, x]> but
  was:<[null]>`), `QueueAutoDownloaderTest` (3 new; the beside line absent: `said once: [pass over 2 outstanding ...] expected:<1> but was:<0>`), `StreamHoldsTest`
  (3: the hold turns to "beside" when an HLS route lands, a teeable item is neither, SABR/podcast/live stay held).
- **Not changed:** `SeveralSharedLinksAllLandTest` still pauses the last (video) share once all three are queued. It would now pass without the pause when the
  share plays over HLS, but a share that plays over SABR is still held, so the pause stays until a nightly has shown which route that link takes.
- **Not proven on a device.** The decision, the intent window and the downloader's behaviour are unit-tested with fakes; no phone or emulator run has
  shown `route=hls`, the beside line and the download completing while the video plays. The next report says it: the `fetching … beside its stream` line,
  then `[download] start`, then `Downloaded`, with `[tee] … downloading it on its own: no audio stream of it is playing through the cache` between.
