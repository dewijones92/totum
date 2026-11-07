---
title: The Pi runs the phone's sound processing
kind: adr
status: accepted
updated: 2026-11-07
---

# 153. The Pi runs the phone's sound processing

- Status: Accepted
- Date: 2026-11-07

## Context

Dewi, 2026-11-06 (option B): sound on a Pi-fed target is exactly the phone's processing, one engine, not a second copy.
[ADR 119](0119-cast-and-sound-code-shared-with-the-pi.md) moved the processing into `:core:sound`, pure JVM, and the
spike measured the whole chain at about 13 times real time on one of the Pi's four cores.

## Decision

- **One PCM chain.** `PcmChain` (`:core:sound`) is the cutter and then the boost on interleaved 16-bit samples, built
  from the same `SilenceCutter` (with `SpeechTrack` when the speech weights load) and `LoudnessBoost` the Media3
  processors use. `process` and `finish` return what to play; `skippedFrames` says what was taken out. Chunking does not
  change the output, and with nothing switched on it is sample for sample the input (both tested).
- **ffmpeg only moves bytes.** On the Pi `PcmPump` (`cli/serve/sound`) reads ffmpeg's decode (`-vn -ac 2 -ar 48000 -f
  s16le`, with `-ss` before `-i` to start part-way) through the chain. `ProcessedSound` builds the two pipelines:
  - **to a speaker:** the processed PCM is written straight into mpv's stdin (`--demuxer=rawaudio`), no re-encode;
  - **to a TV:** the processed PCM goes to an ffmpeg AAC encoder and is served as an ADTS stream by `CastHttpServer`
    from `:core:cast` (`SoundStreams`, a per-offer random token in the path, withdrawn when the item ends).
- **Skip silence follows the phone's per-kind rule.** `MediaContentKind.skipsSilence` (in `:core:domain`, used by the
  phone's `tuningFor` and by the Pi's deck for each item) says talk is cut when asked and music, live and a book's
  chapter never are ([ADR 105](0105-audiobooks-are-books-from-the-archive-and-torrents.md)); the boost applies to every
  kind. The target's own switch (what the Queue tab shows) is unchanged.
- **Not the same as the phone yet:** the Pi always cuts in the smart mode (falling back to the standard cut when the
  speech model does not load) at the default level, and it has no speech gate on the boost. The phone's silence mode
  and speech-gate settings are not carried to a target, so "exactly the phone's processing" holds for the chain and
  the per-kind rule, not for those two settings. Carrying them needs them in `SoundSettings`; owed.
- **Position is the source's.** A processed item's position is mpv's (or the TV's) time plus where it started plus the
  silence taken out, so the number the phone shows and YouTube is told is a place in the original. A processed item has
  no duration from the player; the item's own is used.
- **Changing the sound restarts the item where it was**, on the processed path or off it.
- **A picture is not processed yet.** For a TV showing a video, the sound goes as it comes; the log line says so. The
  phone's DASH path (original video packets plus processed audio segments, ADR 68) needs `CastStreams` split in two
  and is not built.
- **A YouTube stream reaches a TV, mpv or ffmpeg through the Pi's own relay (amended 2026-11-07).** The first build
  gave the receiver the raw `googlevideo` address, which the TV fetches straight through, and YouTube stops such a
  fetch after about the first megabyte (the 0.1.887 run on the LG: `load sent`, 2.8 s of 20 s played, then
  `idle (FINISHED)`; the same cap that made the phone, the Pi's downloads and ADR 68's relay fetch in ranges).
  `StreamRelay` (`cli/serve/cast`) now serves such an address at `/y/<token>.<ext>` on `CastHttpServer` (port 8771,
  `TOTUM_CAST_BASE`), answering any request, with or without a `Range`, by asking YouTube in bounded ranges of 2 MB
  (`ChunkedUpstream` in `:core:cast`, driven by `ChunkedRead`, the arithmetic ADR 68's player source already used, which
  moved there from `:core:playback`). The reply shape is `LocalRanges.replyFor`, the file reply generalised to any source
  of known length (so a TV's seeks, `bytes=N-` and `bytes=-N`, work); the length is the link's `clen`, else asked of the
  server with `bytes=0-0`; a stream whose length cannot be learned answers 502, never a guess.
  - **Only a plain `videoplayback` address on `googlevideo.com` is relayed** (`isYouTubeStream`): a manifest, a live
    segment (`sq=`, `live=1`), a Jellyfin or podcast address, a home copy and a processed stream are not.
  - **The young-URL rule of [ADR 13](0013-playback-start-young-urls-held-bytes-and-copies.md) holds:** `PatientUpstream`
    asks again when YouTube refuses a URL under 15 s old, at the learned window, at most three times; the Pi's resolve
    (`HomeDownloads.soundOf`) still waits for that window before handing a URL out.
  - **Picture or not is unchanged:** the resolve picks the picture or the audio-only address by the deck's choice; the
    relay only carries what it is given, typed from the address's own `mime=`.
  - **Where each player gets it:** a Chromecast gets the Pi's LAN address (`SoundStreams.offerRelayed`, withdrawn on
    stop; with no `TOTUM_CAST_BASE` it gets the raw address and the log says why); mpv and ffmpeg (a boosted or cut
    item, to a speaker or a TV) get `http://127.0.0.1:<port>/y/…` (`SoundStreams.locally`), since they run in the same
    container. Whether mpv's own reader hit the cap was not measured (nothing was run on a speaker); it asks the same
    address the same way, so it goes through the relay too. At most 64 relayed addresses are kept; the oldest is dropped.
  - Logs: `YouTube stream relayed by the Pi` (or `given as it is`), `stream relayed at /y/xxxxxx… type= length=`, each
    request with its range, the probe, a refused young URL, and an early end with the bytes still owed.
- **TVs are driven by `pychromecast`** in a small helper (`server/totum-cli/chromecast_helper.py`) speaking JSON lines
  over stdin and stdout (`CastWire`); the TVs are configured by address (`TOTUM_CAST_TARGETS`), not discovered, because
  mDNS does not cross the container's bridge network.

## Consequences

- Nothing here has run against a TV, a speaker or Chromecast beyond the first run on the LG (2026-11-07), which found
  the volume push (ADR 152) and the stopped YouTube stream above; the unit tests cover the chain, the pump, the wire
  format, the deck, the reporting rule and the relay against a source that only serves ranges. The relay itself has not
  been seen on a real TV.
- ffmpeg is a hard dependency of a processed play, and a missing or failing one ends the item as failed (logged with its
  exit code) rather than silently skipping.
- The Pi spends about 1/13 of a core per processed stream (the 2026-11-06 spike), so several streams fit at once.
- Playback speed is not carried to the Pi yet; a processed or direct stream plays at 1x.
- A TV that is showing a picture ignores the two sound switches; the Queue tab greys them with a note
  ("🎬 Skip silences and boost only work on sound for now …").
- The Chromecast helper's status carries the media id; an idle status ends the loaded item only when it is that item's,
  so a late "cancelled" for the item before cannot end the one that is loading, and one failed connection no longer
  makes the TV unreachable for ever (the helper's problem is reset when a new helper starts). An unused or finished
  sound offer is pruned.
