---
title: Casting plays a stream the phone serves, with Totum's own audio and the original video packets
kind: adr
status: accepted
updated: 2026-10-21
---

# 68. Casting plays a stream the phone serves, with Totum's own audio and the original video packets

- Status: Accepted (prototype; shaping)
- Date: 2026-10-21

## Context

Dewi wants to cast anything Totum plays (YouTube, podcasts, torrents, downloads) to the LG OLED and the Chromecast Ultra
with skip silences, the boost and the rest still applied, ideally without re-encoding, and with Totum on the phone as
the brain: its queue, autoplay, boost and position, the TV only playing. Until now the Cast handover sent the receiver
the item's own URL. For a YouTube video above 360p that is the **video-only** stream (the audio URL rode in a phone-only
extra), so the TV showed the picture with no sound; a download's URL is a file the TV cannot reach; nothing Totum does
to the sound reached the TV.

Probed on 2026-10-21 against both TVs (Pi webcam stills, the C920 microphone, receiver status):

- YouTube's stream hosts grant CORS only to YouTube's own origins, so the Default Media Receiver cannot fetch a DASH
  manifest's segments from them directly; a single progressive URL plays (no CORS needed), but only one track.
- A single growing fragmented MP4 (video copied, audio re-encoded) plays on both TVs but **cannot be seeked**: the
  receiver asks for a byte range the producer cannot serve and buffers for ever.
- A static DASH manifest whose video is YouTube's own WebM fetched **through the phone** (byte ranges relayed, CORS
  added) and whose audio is 4 s AAC segments Totum makes plays and seeks both ways on both TVs; the boost was heard in
  the room (+6.5 dB median).

## Decision

- `TotumCastConverter` is the Cast player's `MediaItemConverter`, the one seam every item reaches the TV through (the
  handover and every queue move). It hands the receiver `http://<phone>:8765/c/<token>/manifest.mpd` and maps the
  receiver's item back to the original, so the phone's UI and queue keep the real item.
- `CastStreams` serves it from inside the playback service: the video by relaying the receiver's ranges to the source
  untouched (`Upstream`), its index and codecs read from the stream's own header (`StreamLayouts`, WebM and MP4); the
  audio decoded, run through the **same** `BoostingAudioProcessor` the phone uses, re-encoded to AAC and cut into
  segments on demand (`ProcessedAudio`, `AudioTranscode`, `Fmp4Aac`). Only the audio is re-encoded, never the video.
- While casting, the gapless armer puts the next queue item behind the playing one at once, so the TV crosses over by
  itself and its remote's next works; Totum adopts the crossover as it does locally. An item the receiver drops near its
  end counts as ended (`RemoteEnd`); a crossover is judged ended or skipped by Totum's own clock, since the receiver
  reports the full duration either way.
- **Skip silences** on an audio-only item runs the phone's own `SilenceCuttingAudioProcessor` (same mode and speech
  model) before the boost. The manifest keeps the source's length, so the TV seeks a static timeline (a growing HLS
  EVENT playlist was tried on 2026-10-21: it started at 0:00 and ended cleanly but the LG would not seek forward on it);
  `CastTimeline` maps TV time to source time from where each segment ends, and `TimeMappedCastPlayer` gives Totum
  source time for position, seeks and progress. Past the cut audio's real end the TV gets silent segments, and Totum
  moves it to the next queued item (or stops it) as soon as it reaches that end. Whether an item cuts silences is
  recorded on the item when it is built (`EXTRA_SKIP_SILENCE`, Totum's music-plays-plain rule), not read from whatever
  was playing when it was armed. Video items keep their silences (Dewi's choice).
- Speed reaches the TV through the receiver's own playback rate (measured 1.5×: 20.3 s of media in 13.5 s).
- While casting, the phone's stall watchdog neither rebuilds nor ends an item: the TV buffers from Totum's own
  stream, and the cast decides ends. The audio source is read with Media3's `MediaExtractorCompat`, which seeks by the
  stream's index (Android's `MediaExtractor` scanned a WebM linearly to seek).
- The service holds a wake lock and a Wi-Fi lock while it serves, so a cast survives the phone being locked; leaving the
  cast lets go of the video surface so the phone's picture comes back (`PictureWatch` logs the frames that prove it).
- Debug builds read `files/cast-base-url.txt` for the address to advertise (the emulator bench reaches the TVs through an
  ssh tunnel on the Pi); release builds advertise the phone's Wi-Fi address.

## Consequences

- **Captions are cleaned for the TV** (2026-10-24, report 0.1.707: "when casting the subtitles seem to go half off
  screen"). YouTube's auto-generated WebVTT places every cue with `align:start position:0%` (and sometimes `line:`),
  and word timings (`<00:00:00.560><c> word</c>`). The phone relayed it untouched; a receiver that centres the cue
  box on its position draws half of each line off the left edge. `TvCaptions.clean` now strips cue settings and
  word-timing tags, so the TV places captions with its own defaults (bottom centre); the relay logs what it removed,
  so the next cast report shows whether those settings were there. Not seen on a real TV yet. `TvCaptionsTest`, red
  first.

- Review of 2026-10-23: only unicast routes count as a VPN carrying the TV's network (a VPN's excluded routes are
  `RTN_THROW`, which said "in the way" when it was not), and the hand-over, the TV's first request and the check
  update the warning under one lock (the check runs 12 s after the hand-over), so a request arriving mid-warning cannot leave it stuck. Neither has a unit test:
  `RouteInfo` cannot be built in a test, and the race needs two threads interleaving at one statement.

- The phone relays the video bytes over Wi-Fi and encodes the audio while casting; the TV decodes.
- A downloaded video's picture: its video track is remuxed (stream copy, no re-encode) with the bundled ffmpeg into a
  WebM with its Cues at the front, or a fragmented MP4 with a global `sidx` when the codec cannot go in WebM, cached in
  `cacheDir/cast-remux`, and served by byte range beside the processed audio from the same file (Gangnam Style, 53 MB:
  0.2 s). The bundled binaries' names live once in `:lib:common` (`BundledBinaries`).
- **Formats are chosen for the TV, from measurements, not spec sheets.** While a Cast session is up, the resolver's
  codec support is the TV's (`CastScreen`, by the device's model name) and the height cap is the TV's; a cached
  resolve records which screen it was built for. Starting or stopping a cast replays the current video at its
  position so it switches stream. Measured 2026-10-21 through the relay: the LG OLED65C24LA's Cast player plays VP9 at
  2160p but errors on AV1 at any size (the TV itself decodes AV1); the Chromecast Ultra plays VP9 1080p but errors on
  VP9 2160p and AV1. So both are VP9/H.264, the LG up to 2160p and the Ultra up to 1080p; unknown devices are
  VP9/H.264 1080p. **HEVC** measured 2026-10-21 (1080p 8-bit DASH, `hev1` and `hvc1` tags): both TVs play it, so a
  downloaded x265 torrent casts without re-encoding (its video is remuxed into an MP4); HEVC Main10 is not measured.
  A torrent still streaming as **Matroska** (H.264 or HEVC) is **remuxed on demand** (Dewi's choice, 2026-10-22):
  `MkvVideo` reads the header, the video track (often not the first) and the Cues by byte range, and each cue-to-cue
  span becomes one fMP4 segment when the TV asks for it, its frames copied byte for byte (Matroska stores H.264/HEVC
  length-prefixed exactly as `avcC`/`hvcC` describe), decode times derived for B-frames, a signed `trun`; the manifest
  carries the cues as a `SegmentTimeline`. Every decoded frame of the remux matched the source in tests (HEVC and
  H.264, B-frames, open GOP, audio track first), and both TVs played a 2-minute HEVC MKV through the phone with no
  jumps. Laced or compressed tracks, and MP4s that are not fragmented, go as sound only and say so; an audio-only
  file sends no picture and no warning.
- **Handing over** (2026-10-22, on the real Ultra): the cast player is told to play *before* it is given the item,
  because it sends the receiver's load (with its autoplay) as soon as it has the item, and a play sent after that load
  did not stick: the TV sat paused. A replay for the TV's formats waits for the cast player to settle, takes position
  and play intent from the side being left (the cast player's first state is still loading, at 0 ms and paused), and
  is skipped when what the TV was sent is already its best (no refusal, height at the TV's maximum): the replay cost a
  second load. Releasing a waiting source happens off the main thread (stopping a cast crashed v0.1.657 with
  `NetworkOnMainThreadException`: closing an HTTP source drains its body).
- **Video skip silences while casting stays off** (Dewi's rule: only if both TVs cope). Measured 2026-10-22 with a
  60 s clip and a manifest of 10 periods each skipping a 1–1.5 s span: Shaka, the Ultra and the LG all jump up to ~2 s
  at every boundary (the next period can only start at a keyframe), so 48.6 s of kept content played in 42–43 s: about
  6 s of real content lost. The control (one period) played exactly. Short 2× speed bursts sent from the phone, by
  contrast, play on both TVs with no buffering (2.1–2.3× in the burst, ~1× after; the first burst takes about a second
  to apply), so speeding through long pauses is possible; not built.
- **The TV is never handed a video it cannot show.** The replay on a cast change is triggered by the Cast session
  itself (`CastTarget.screens`), not by the player's remote flag, which flipped about 9 s late on Dewi's Pixel, so the
  first item kept the phone's AV1 and the Ultra showed nothing (report 20261021T190400-f5d37fb7). As a safety net,
  `CastStreams` drops a video the TV cannot decode or Totum cannot read and sends **sound only** (Dewi's choice). The
  refusal travels with the TV's name to the phone's UI as session extras (`CastStatus`), and a strip on the mini and
  full player says "📺 Casting to <device>" and, when it applies, why the picture is missing.
- **Subtitles on the TV** follow the phone's Subtitles button (Dewi's choice). Media3 1.10.1's cast player keeps the
  receiver's current tracks and ignores track-selection parameters unless it is given a `CastTrackSelector`;
  `TotumCastTrackSelector` picks the text track in the chosen language and leaves audio and video as they are. Only
  WebVTT tracks are offered to the TV, and items with silences cut carry none, because their times no longer match.
  The phone fetches each track for the TV directly, as its own player does: neither goes through the YouTube politeness
  gate yet (open, see the feature doc).
- **Start and seek latency** (Dewi's "casting stuck": 5–9 s on his Pixel, v0.1.656). The audio producer pays for each
  restart; the TV waits for three or four segments before it plays. The codec loop drains fully instead of waiting on
  timeouts; a spare source is kept open for the next start or seek (used only under 20 s old, retried once on a fresh
  one if it fails before producing anything; Media3's extractor turns a failed read into an end of input, so an end
  more than 3 s or 2% short of the duration is treated as a failure, unless it repeats at the same place); after a seek the packets before the target are skipped without decoding
  (the extractor lands on the previous cue, up to 6 s early); encoder input buffers are filled from several decoder
  chunks. Each restart logs its open, seek and per-stage times. On the stand-in: start 1.8 → 1.4 s, seeks 2.2–3.3 →
  1.6–2.0 s. Not yet re-measured on a real TV or the Pixel.
- **A stand-in receiver** tests the receiving side without a TV: Shaka Player (what the Cast receiver plays DASH with)
  in a private headless Edge on Windows, fed by a debug-only hook that serves the playing item exactly as a cast would
  (`tools/cast-bench/standin/`). It covers manifests, segments, seeks, start time, subtitles and the sound-only path; it
  cannot cover the Cast session (queue moves, the TV remote, ends), which needs a real device, because Android checks a
  Cast device's Google-signed certificate.
- **Android 17 local network protection:** Totum targets API 37, so it needs `ACCESS_LOCAL_NETWORK` to find or reach a
  TV. The first tap on Cast asks for it (Nearby devices), then opens the picker. Dewi's Pixel 7 on Android 17 found
  no devices on v0.1.654 (report 20261021T165118-4d9542f7, "couldn't find devices??"); fixed on v0.1.656, which found
  the Ultra and cast to it from his Pixel.
- Not yet: live streams (given to the receiver as they are), an opt-in re-encode for a video the TV cannot show (it
  gets sound only and the strip says why), and a TV-side up-next UI (Dewi chose the free Cast queue for now).
- Seeking with the TV remote restarts the audio producer at the new segment; expect a second or two of buffering.
- With skips on, the TV's own seek bar shows the source's length and its clock runs behind the episode's; Totum's
  own seek bar, resume points and transcript use episode time.

## Amended 2026-10-22: reaching the TV, a quiet receiver, the first load

- **Address.** The phone advertises its Wi-Fi address, else Ethernet, never a VPN's (`CastAddress.choose`). A VPN
  network carries the Wi-Fi transport on Android, and report 20261022T115247 had the TV handed `10.13.13.2`.
- **Unreachable.** If nothing at all reaches the phone's server within 12 s of a hand-over (`CastReach`, judged by
  any request rather than per item, so the next item queued behind the playing one never trips it), the casting
  strip says the TV is not fetching (`CastProblem`).
- **While the receiver loads** it reports an empty playlist for ~10 s. The controller shows the item it asked the
  receiver to load (the hand-over's or a skip's), marked `castLoading`, for up to 30 s (`CastLoadHold`); `CastMoves`
  waits past it. Nothing is held that was not asked for, so a finished cast leaves no ghost.
- **Cast audio** reads YouTube through `ChunkedDataSource`, as the phone's player does: unranged, YouTube held it to
  ~1x real time and a 4 s segment took ~2 s to arrive.
- **The first load.** When the phone's own stream is one the TV cannot decode (AV1 on a Chromecast Ultra), the replay
  for the TV's formats starts at the move instead of after the doomed load is refused.

## Amended 2026-10-22 (later): the TV picks its quality within a ladder

Dewi: *"the best seamless and fastest way is to just stream what is currently buffered on the phone but in parallel
it's getting the highest quality stream that the TV supports"*. A YouTube video goes to the TV as **one adaptation set
of up to three qualities** (`CastLadder.choose`): the best the TV decodes, the phone's own stream when it is in that
codec family, and the next ones down. Each is relayed on its own path (`video/r<n>`), and the TV's player moves
between them itself, so there is no replay and no restart when the phone's stream was lower than the TV's best.
The launcher passes the video-only streams it already resolved (`VideoRung`, `EXTRA_VIDEO_LADDER`); their codecs are
read from the streams themselves before choosing (`CastRungs`), because the signed-in lookup can leave them blank and
an unknown codec would otherwise let AV1 or a mix of families reach a TV that cannot play them (`CastRungsTest`, seen
red: it offered AV1 1080p with H.264 720p to a Chromecast Ultra). Which quality the TV is fetching is logged and shown
on the casting strip ("720p picture on Living Room, going up to 1080p"). Measured on the Ultra: VP9 1080/720/480
offered, 1080p fetched from the start, no replay. Not done: a text note on the TV itself at the switch.

### Amendment (2026-10-22): the phone keeps the player while the TV plays, and replays less

Report 0.1.671: *"can't see the player when casting??? so how can I pause"*. After a replay on the TV, Media3's
`CastTimelineTracker` (1.10.1) can report the current item as `MediaItem.EMPTY`: it keys items by content id, and
during a load the old item id picks up the new content id, so when the old item leaves the queue the new one's entry
goes with it. The TV played on; the phone saw an item with no id, so the mini player and full player vanished. Now an
unnamed item on the receiver is shown as the one Totum sent it last, with the TV's real position and state
(`CastLoadHold.unnamed`, `CastLoadHoldTest`, seen red), and a warning names it in the log.

Two replays are dropped. An item cast in Listen mode goes as sound only by choice, so there is nothing to wait for and
no replay (it replayed after 5 s of "sent nothing yet"). And "as good as it gets" is the best height on offer that the
TV can decode, not the TV's maximum (`CastScreen.bestOf`, `CastScreenTest`, seen red): a 1080p video on the 4K LG had
replayed for nothing.

Each ladder rung now declares the bandwidth measured from its own URL (`clen × 8 / dur`), falling back to the height
table only when the URL lacks them, because the TV picks rungs by that number and the table under-declared 4K VP9
(`DashLadderTest`, seen red). The offer is logged ("ladder offered at 1080p=…kbps, …"), with how long probing took.

Review corrections (2026-10-22): the stand-in for an unnamed receiver item is used only while the player is READY; while
it loads, the load hold still shows "loading" (so a hand-over waiting for the TV to settle is not fooled); playing on the
phone forgets the TV's last item, so a later cast cannot show a stale one; and `RemoteEnd` keeps the clock of an
unnamed item as the continuation of the last named one, so a long pause then a stop is not read as an ending.

## Amended 2026-10-23: a VPN that carries the home network is named on the strip

Report 0.1.698 (*"peep show failed to cast???"*): the TV asked the phone for nothing; the phone had a VPN up
(`wifi+vpn 10.13.13.2`), and Dewi confirmed he was on it. A VPN whose routes cover the address the TV is given
(`0.0.0.0/0`, or the home subnet) can send the phone's replies into the tunnel, so the TV never connects. Now the
address line logs each VPN's routes, `CastAddress.vpnCarries` checks them, and when one covers the address the cast card
says so at once (🔒 "A VPN on this phone is carrying your home network … turn it off, or exclude Totum in the VPN app"),
clearing by itself if the TV fetches after all. Not yet proven on his phone: the next report's route list will show
whether this VPN covers 192.168.0.x. Tests: `VpnInTheWayTest`.
