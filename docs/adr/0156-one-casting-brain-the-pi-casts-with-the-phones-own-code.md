---
title: One casting brain, the Pi casts with the phone's own code
kind: adr
status: accepted
updated: 2026-11-28
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
  which the phone also hands over as it is (a YouTube HLS manifest is no longer one of them: see the 2026-11-28 amendment).

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
back to the DASH manifest, as before, and logs why. A live item is started at the live edge, with no `--start`. The call goes through the
one `PoliteGate` (at most 4 in flight, 429 slow-down) and `BotBackOff` of [ADR 51](0051-a-polite-youtube-client.md)'s
`:lib:common` classes, held by the Pi process as one `Politeness` (the Pi had no gate before; its resolver, InnerTube and
yt-dlp calls now share this same gate and back-off, [ADR 51](0051-a-polite-youtube-client.md)'s update of 2026-11-07). A bot check backs the lookup
off, and the answer is kept in memory per video until ten minutes before the address's own `expire` (30 minutes when it
has none), so a looping station does not re-run yt-dlp; failures are not kept.
Not verified on the Pi's speaker from here: the coordinator runs it.

## Amended 2026-11-28: a YouTube video that is offered only as HLS is served and relayed, never handed over

Seen on the Pi on 28 November at 03:43 (v0.1.978, target `lg-tv`, video 3Uavv96zDkM): YouTube offered six HLS formats and none
with its own audio, so `HomeDownloads.castOf` built no ladder (`no stream with its own audio to build a ladder from (6 offered)`),
the item's address was `manifest.googlevideo.com/api/manifest/hls_playlist/…`, and `CastStreamServer.givenAsItIs` declined it
because it names a manifest. `ChromecastPlayer` then loaded that address on the LG as `video/mp4` (`given as it is as DASH`) and
the receiver went `idle (ERROR) after 0ms`. A Cast receiver's web player needs CORS on a manifest and every segment, and
googlevideo grants it only to YouTube's own pages (`cast-receiver-facts`, measured 2026-10-21), so that load could never work. The
earlier bullet above ("a third party's manifest, which the phone also hands over as it is") was true of the case it described and
wrong for this one; the phone's `TotumCastConverter` had the same fallback and the same hard-coded DASH content type.

- **`HlsRelay` (`:core:cast`) serves YouTube's HLS from the one stream server.** `CastStreamServer.urlFor` checks, before it looks
  for a picture to build, whether the item's address is an HLS manifest on googlevideo (`HlsRelay.needs`: `googlevideo.com` and a
  path ending `.m3u8` or holding `/hls_playlist/` or `/hls_variant/`). If so it returns `…/c/<token>/relay.m3u8` on its own address.
  A request for that file fetches the real playlist from YouTube, rewrites it and answers; every `http(s)` address in it, in a
  segment line or a `URI="…"` attribute (variant playlists, alternate renditions, `EXT-X-MAP`, `EXT-X-KEY`, whatever else carries
  one), is resolved against the playlist's own address and replaced by a short name on this server (`x<n>.<ext>`, the extension
  kept so a receiver can still tell `.ts` from `.m4s`). A request for such a name is fetched from YouTube and streamed back with
  the `Range` it carried, the `Content-Range` it got and CORS headers (`CastHttpServer` adds them to every answer).
- **Why a relay and not a manifest built from the variants.** The existing brain builds DASH or HLS from a probed video stream plus
  its own processed sound. YouTube's HLS variants are muxed MPEG-TS (picture and sound in one stream); there is no separate video
  stream to probe, relay by range or pair with Totum's sound, so nothing of the DASH and HLS builders applies. Rewriting is the
  smaller change: `Upstream`/`PatientUpstream` (young-URL retry), `CastHttpServer` (CORS, ranges), `CastReach` and `CastFailures` are
  reused as they are. `StreamRelay` is not: it relays one plain `/videoplayback` file with a length it can learn, which a playlist
  and its segments are not.
- **Playlists are read afresh on every request, never kept.** The same code therefore serves a VOD playlist and a live
  `hls_variant` one (a new segment appears under a new name, an old one keeps its name). The address table is bounded at 20,000
  entries (oldest forgotten, said once in the log).
- **The content type follows the address.** `CastFormat.mimeOf(address, format)` is `application/x-mpegURL` for a relay address and
  the format's own for everything else; `ChromecastPlayer` (the Pi) and `TotumCastConverter` (the phone) both use it, so the
  phone no longer says `application/dash+xml` of an HLS playlist.
- **Every format is relayed, not only the DASH ones.** A DLNA, AirPlay or Kodi device could fetch googlevideo itself, but one rule
  ("Totum never hands a device a googlevideo manifest address") is simpler to state, log and test than a per-device one, and the
  relay costs the Pi one stream it was going to carry anyway. Not seen on those devices.
- **Not changed:** a third party's HLS manifest and a googlevideo DASH `.mpd` are still given as they are; the away-from-home
  public route (`openFor`) refuses them as before; picture and sound are YouTube's, so silence cutting and the boost do not apply to
  a relayed HLS stream, and a sound-only request still carries the picture.
- **Logged** (`cast`, each `dewidebug`): the decision and its reason when the address is handed out; every playlist read, with
  its variant, segment and rewritten-address counts; any part the source refused, with the status; the first three parts and every
  25th; and the release. A refusal counts towards `CastFailures`, so three in a minute reach the strip as the reason.
- **Not proven:** it has been run only against a fake upstream (`CastStreamServerHlsRelayTest`, `ChromecastHlsOnlyTest`). Whether
  the LG's receiver plays YouTube's MPEG-TS HLS through this relay, whether `manifest.googlevideo.com` answers the JVM's default
  agent, and live `hls_variant` playlists are for the first run on the real TV.

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

## Amended 2026-11-28 (second): the relay carries captions, and a name it never listed is not a failure

Real runs on 28 November 2026 (Pi `totum serve` v0.1.984).

**Captions on the HLS relay.** YouTube 3Uavv96zDkM, cast to the LG, had eight resolved subtitle tracks and the receiver reported none
(`the receiver offers 0 caption track(s) []`, `captions en` asked): the relay path never looked at `CastItem.subtitles`.

- **Chosen: an `EXT-X-MEDIA:TYPE=SUBTITLES` group in the rewritten master playlist**, not a side-loaded track list. The cast load is only a
  URL and a content type (the phone's Media3 converter and the Pi's helper both), so there is no place in it for tracks that is shared by
  the two, while the DASH path already hands the receiver its tracks inside the manifest and the receiver's HLS player reads the same thing
  from a master playlist (that a Cast receiver's HLS player reads WebVTT subtitle renditions is from Google's Cast documentation as
  remembered, not checked from here, and not seen on the LG).
  Keeping the tracks in the playlist also means the phone's cast and the Pi's get them from the one place, `HlsRelay`.
- `HlsRelay` adds one `EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="totum-subtitles",NAME,LANGUAGE,DEFAULT=NO,AUTOSELECT=NO,URI="subtitles/N.m3u8"`
  per track before the first variant and `SUBTITLES="totum-subtitles"` on every `EXT-X-STREAM-INF`. `subtitles/N.m3u8` is a one-segment VOD
  playlist as long as the item (a long fixed length, logged, when the item's length is unknown) pointing at `subtitles/N.vtt`, which is the
  track fetched from where it was resolved, SubRip made WebVTT, cleaned and placed for a TV: the same `SubtitleFiles.reply` the DASH path's
  `subtitles/N.vtt` now uses (one copy of that code, with the fetch passed in so the relay's own upstream is used).
- **Left alone:** a master that already offers a subtitles group, a media playlist with no variants (nothing to attach a group to), and an
  item with no tracks. Each says so in the log.
- **The phone takes the same path.** `TotumCastConverter` calls `CastStreams.urlForChromecast`, which builds the same `CastItem` (with its
  `subtitles`) and calls `CastStreamServer.urlFor`, which reaches `relayHls` for a YouTube HLS-only address, so the phone's relayed cast gets the
  group too. Not run on a phone.
- **Not proven:** that the LG's receiver lists the group's tracks as `MediaTrack`s (the Pi's helper's `tracks` event is the evidence; the
  DASH path rests on the same assumption, ADR 191), and the cue timing: the WebVTT has no `X-TIMESTAMP-MAP`, so its times are taken as media
  time from 0, which is right if YouTube's MPEG-TS starts at 0 and early or late by the stream's start offset if it does not.
- Logs: `HLS master now carries N caption track(s) as an EXT-X-MEDIA TYPE=SUBTITLES group …`, `HLS master carries no caption tracks of
  ours: <why>`, `HLS subtitle playlist N (lang) is one WebVTT segment of Ns`, then the existing `subtitles <lang> for <id>: upstream …`
  lines. The line that hands the relay address out now also says `captions=N[langs] durationMs=…`.
- Tests: `HlsRelayCaptionsTest` (red: `expected:<2> but was:<0>` EXT-X-MEDIA lines; the subtitle playlist and `.vtt` answered 404).

**A name the relay never listed is not a source failure.** Kodi 21.3 on the Fire Stick (06:22) asked the relay for an empty name (the bare
session address or a trailing slash). `HlsRelay` answered 404, and `relayedHls` counted that as a failure of the source, so three of them
would have told Dewi "❌ couldn't start" over a cast that played fine.

- `relayedHls` now asks the relay whether it lists the name (`HlsRelay.lists`: the entry, a `subtitles/N` that exists, or an address it
  handed out). If not, the answer is a 404 that never reaches `CastFailures`, and nothing is asked of YouTube. The first such request is
  logged once with the exact request path: `HLS relay was asked for "<path>" (the name "<name>"), which it never listed; answered 404 …`.
  A real refusal by YouTube of a name the relay did list still counts, as before.
- Test: `HlsRelayUnlistedNameTest` (red: `a request for a name the relay never listed was counted as a failure expected:<[]> but was:<[(yt1,
  the source answered 404 for )]>`).
