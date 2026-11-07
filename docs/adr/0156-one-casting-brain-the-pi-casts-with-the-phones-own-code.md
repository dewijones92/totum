---
title: One casting brain, the Pi casts with the phone's own code
kind: adr
status: accepted
updated: 2026-11-07
---

# 156. One casting brain, the Pi casts with the phone's own code

- Status: Accepted
- Date: 2026-11-07

## Context

The phone casts through one piece of code ([ADR 68](0068-cast-a-stream-the-phone-serves.md)): it chooses a picture the TV
can show from the item's ladder, builds a static DASH manifest, relays the original video packets, serves the sound as
processed AAC segments, adds captions, handles live ([ADR 69](0069-live-streams-play-a-manifest-totum-builds.md)) and
falls back to sound only with a reason. [ADR 119](0119-cast-and-sound-code-shared-with-the-pi.md) moved the plain pieces
into `:core:cast` but left `CastStreams`, `ProcessedAudio` and the live relay in `:core:playback`, so the Pi grew a
simpler second path ([ADR 153](0153-the-pi-runs-the-phones-sound-processing.md)): one URL and a mime for a Chromecast, a
`/s/` AAC stream for processed sound, `/f/` for files, `/y/` for YouTube, no live. Dewi's 2026-11-07 question, "we have
casting that worked from the phone for days, are we reinventing the wheel?", was fair: the Pi's path could not send a
picture and processed sound together, could not seek a processed item, could not play a live stream, and any fix to the
phone's cast would have been missing from it.

## Decision

- **`CastStreamServer` in `:core:cast` is the one brain.** It is `CastStreams` with Media3 taken out: the same sessions,
  rung choice (`CastRungs`, `CastLadder`, the TV's measured `CastScreen`), manifest (`DashManifest`), relayed video,
  remuxed or local video, captions, live relay and sound-only fallback, moved not rewritten. It takes a `CastItem` (the
  item's address, separate audio address, ladder, whether it is being listened to, whether to cut silences, length and
  captions) instead of a Media3 `MediaItem` with extras.
- **Four ports carry what differs.**
  - `AudioEngine`: probe, transcode a `TranscodePlan` into a `TranscodeSink`, make a silent frame. The phone's
    `MediaCodecAudioEngine` (MediaCodec, Media3 processors, the spare extractor) and the Pi's `FfmpegAudioEngine` (ffmpeg
    decode, `PcmChain` from `:core:sound`, ffmpeg AAC encode, ADTS frames cut into the same fMP4 segments) both do this.
    `ProcessedAudio`, the segment cache, producer restarts, early-end handling and the silent tail above the engine are
    shared.
  - `CastHostPort`: the address a TV fetches from, wake locks, whether a VPN is in the way. The phone's `CastHost`; the
    Pi's is `TOTUM_CAST_BASE`.
  - `CastTvPort` (already there): which TV and what it can show, what was sent, a problem. It gains
    `current(mediaId)` so one server can serve several TVs. The phone's `CastStatus`; the Pi's `PiCastTvs`, which builds
    the screen from the model the Chromecast helper reports and turns a refused picture or a problem into a notice the
    target shows.
  - `MediaTools` (ffmpeg, ffprobe, two folders): `VideoRemux` and `EmbeddedSubtitles`, which only ever needed those.
- **Moved to `:core:cast` unchanged:** `LiveRelay`/`LiveCasts`, `LocalManifests`, `VideoRemux`, `EmbeddedSubtitles`,
  `ProcessedAudio`, and `TranscodePlan`/`TranscodeSink`/`EarlyEnd`. Staying in `:core:playback`: `AudioTranscode`,
  `SilentAac`, `CastStatus`, `TotumCastConverter`, `TimeMappedCastPlayer`, `ScreenPlayer`, `PcScreens`, and `CastStreams`
  as a thin adapter (a `MediaItem` becomes a `CastItem`, `CastHost` and the engine are wired in).
- **The Pi's TV targets load what the phone would load.** `ChromecastPlayer` hands the item to `PiStreams`, which owns
  one `CastStreamServer` on `TOTUM_CAST_PORT`, and loads `…/c/<token>/manifest.mpd` (or `live.mpd`) with
  `application/dash+xml`. For a YouTube video the deck asks `HomeDownloads.castOf`, which resolves with the same
  `VideoResolver` the phone uses and builds the phone's inputs: the chosen video stream, its own audio stream and the
  ladder of every quality that has one. A home copy, a Jellyfin address, a podcast and a file on the Pi are cast as the
  file or address they are, the core deciding picture or sound by name and by bytes. A TV that cannot show the video gets
  sound only and the target's `problem` says why. `PiStreams` keeps the per-play sound settings the engine needs.
- **Silence cutting keeps the phone's rule:** only with no picture. The TV's position is mapped back to the source
  through the same `CastTimeline`, seeks are mapped forward, and when the cut audio ends before the source does
  the player stops the TV so the queue moves on (the phone's `TimeMappedCastPlayer` job).
- **Live works on the Pi.** The Pi's resolver builds the live manifest as the phone's does (`LiveStreamManifest`,
  published by `LocalManifests` on loopback). A TV is given the relayed `live.mpd`; a speaker (mpv, or ffmpeg when the
  sound is changed) is given the same relayed manifest on `http://127.0.0.1:<port>`. The segments are relayed by the Pi.
- **The wire to the TV is the one device-specific seam, and it passes what the phone passes.** `CastWire.load` carries
  the content type (`application/dash+xml` for everything the core serves) and `streamType`. The phone sends `BUFFERED`
  for every item including live: Media3's `DefaultMediaItemConverter` sets `STREAM_TYPE_LIVE` only when the item has a
  `LiveConfiguration`, which Totum never sets (read from the converter's bytecode, 2026-11-07; not seen on a TV). The
  helper passes `stream_type` to `pychromecast`.
- **Removed from the Pi:** `SoundStreams` (`/s/` AAC streams and `/f/` files, replaced by the core's segments), and the
  TV-facing use of `StreamRelay` for a stream the core serves. `StreamRelay` stays for exactly two things: ffmpeg and mpv
  reading a YouTube stream over loopback (the 1 MB cap, [ADR 153](0153-the-pi-runs-the-phones-sound-processing.md)),
  and the case the core declines to build a manifest for (a single muxed YouTube stream, a third party's manifest),
  which the phone also hands over as it is.

## Amended 2026-11-07: a live stream on a speaker is YouTube's HLS manifest, not Totum's DASH

The first live item on a speaker (a jazz radio, 09:23) ended after about three seconds with mpv exit 2 although the core
had built and served the manifest (`upstream 200`). mpv's DASH demuxer is ffmpeg's `dash`, which reads Totum's
`type="static"` 12-hour manifest as a recording: reproduced on this laptop with the same mpv (0.37, the image's Ubuntu
package is 0.37 too) against a stand-in origin, it asks for every segment from the start of the timeline, runs past the
live edge into 404s and gives up ("Error when loading first fragment of playlist"). The TV is not a demuxer problem
(the LG plays it, verified) and stays on the DASH path. For a speaker the Pi now gives mpv, or ffmpeg when the sound is
changed, what the old speaker service gave it: the HLS manifest from `yt-dlp -g -f bestaudio/worst --extractor-args
youtube:player_client=web_embedded` (`YtDlpLiveHls`, using the image's `/opt/venv` and `TOTUM_JS_RUNTIME`), asked for in
`PiStreams.locally` when the address is one of the core's live manifests. If yt-dlp cannot answer (60 s cap) it falls
back to the DASH manifest, as before, and logs why. A live item is started at the live edge, with no `--start`. It is one
yt-dlp call per live play, outside the polite gate of ADR 51 (one request, only when a speaker starts a live item).
Not verified on the Pi's speaker from here: the coordinator runs it.

## Consequences

- A fix to rung choice, the manifest, captions, live or the sound-only fallback is made once. The phone's behaviour is
  unchanged by the move (its requests, manifests and log lines are the same text); its instrumented tests
  (`CastAudioFromADownloadTest`, `CastingFivePointOneSoundTest`) and the cast-bench stand-in still run against
  `CastStreams`. They were not run in this change (no device); the core's unit tests and the Pi's new tests were.
- Sound on a TV now always goes through processed segments, as on the phone, even with nothing switched on; a Pi doing
  that costs an ffmpeg decode and encode per playing TV item.
- Not seen on a real TV, speaker or Chromecast: the manifest, the relayed picture, the Pi's segments, live on mpv and on
  a TV, the stream type. The first run on the LG is the test of all of it.
- Owed: the phone's silence mode and speech gate are not in `SoundSettings` (ADR 153); captions on a Pi target come
  only from a downloaded MKV's own tracks and a podcast or video that carries none; the TV's `problem` text is plain
  English on the Pi and a string resource on the phone (two copies of a handful of sentences, not of logic).
- The image downloads the CLI from the GitHub release named by `TOTUM_VERSION`: this change reaches the Pi only after a
  release is built and the image is rebuilt with it.
