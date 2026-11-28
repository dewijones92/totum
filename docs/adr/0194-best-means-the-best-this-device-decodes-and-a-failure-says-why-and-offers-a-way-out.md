---
title: "Best" means the best this device decodes, and a playback failure says why and offers a way out
kind: adr
status: accepted
updated: 2026-11-27
---

# 194. "Best" means the best this device decodes, and a playback failure says why and offers a way out

- Status: Accepted
- Date: 2026-11-27

## Context

Dewi's reports of 21 to 24 November (v0.1.944):

- **20261123T094934 (Fire tablet).** A download asked for "best" took 4K VP9, a 2.7 GB file the tablet cannot play: it
  decodes VP9 only up to 1080p. "Best" is `DownloadChoice.Video(null)`, so no height reached
  `codecsToAvoid(null)`, which asks `canDecode(vp09, null, null)`; without a size the answer is yes. Streaming had
  withheld such heights all along (`canDecode` per height), so the same video offered 1080p to watch and 4K to download.
- **20261122T070821.** A Dolby E-AC-3 decoder stopped on one file (`ERROR_CODE_DECODING_FAILED`, `format_supported=YES`,
  `c2.dolby.eac3.decoder` UNKNOWN_ERROR) and the toast said "this phone can't play this sound (E-AC-3)", which is
  untrue: the same phone played E-AC-3 on 7 November. Each failure also produced two identical `ui toast` lines 2 ms
  apart.

## Decision

- **One seam for what a device decodes.** `VideoCodecSupport` answers `canDecode(codec, width, height)`; streaming's
  withholding, `codecsToAvoid`, and now `bestHeight()` and `reach(family)` all read it (`usable`, the one predicate
  `codecsToAvoid` already used: decodable at that height, and AV1 only in hardware). `bestHeight()` is the highest rung of
  a height ladder (4320 to 144) that any usable codec reaches, and null (uncapped) when the top rung is reachable or
  nothing is known.
- **"Best" downloads are capped at that height.** `EngineDownloadStrategy` takes `bestHeight` and, with no explicit
  height, builds the same capped selector an explicit height does:
  `bv*[height<=H][vcodec!^=?…]+ba/b[height<=H]/bv*[vcodec!^=?…]+ba/b`. An explicit height still wins. The Pi's
  downloads use the same code through `PhoneCodecs`, which implements `VideoCodecSupport` from the decoders the phone
  told it, so "At home" gets the same answer. The selector log line carries `deviceBestHeight=`.
- **A failure says whose fault it is.** `ErroredFormat` and `PlaybackProblem.CannotDecode` carry whether the renderer
  said the format is supported (`ExoPlaybackException.rendererFormatSupport == FORMAT_HANDLED`, else the message's
  `format_supported=YES`), the mime type and the size. `format_supported=YES` is "this phone's E-AC-3 decoder stopped on
  this sound"; anything else keeps "this phone can't play this". A video beyond what its codec reaches says "VP9 at
  3840×2160 is beyond this device, which reaches 1080p".
- **The toast offers a way out where there is one** (`wayOutOf`, one function, through the existing Play on… and
  download seams): a torrent, a Jellyfin item or a file the Pi holds is offered "▶️ Play on <the first Pi device that is
  on>" (`PlayOnOption.plays` respected, a sleeping device last); a downloaded video beyond the device's reach is
  offered "⬇️ Get a 1080p copy", which deletes the undecodable file and downloads again capped. 🎧 Listen still comes
  first for a picture the phone cannot show.
- **A failure is told once.** `PlayAttempts.announceProblem` drops the same item's problem repeated within a second,
  and the toast collector keeps a process-wide `DuplicateTaps` guard on identical words, each saying why it dropped
  one and which collector it was, so a report settles whether it was two emissions or two collectors.

## Consequences

- Dewi's tablet downloads 1080p where it used to fetch 2.7 GB it could not play. A device that decodes everything is
  unchanged (`bestHeight()` is null, the selector is the old `bv*+ba/b`).
- The "At home" refusal that preceded the tablet download in the 23 November log ("Not signed in", then the tablet
  fetched it) was not found to fall back automatically: `HomeDownloads.start` notifies the refusal and stops. The
  refusal line now says so explicitly ("nothing falls back to the phone by itself").
- Tests: `ABestDownloadIsTheBestThisDeviceDecodesTest`, `PlaybackProblemTest`, `WayOutTest`,
  `ADuplicateProblemIsToldOnceTest`, `DuplicateTapsTest`; instrumented words in `AnUnplayableVideoSaysWhyWordsTest`.
- Not built: probing real hardware per profile (10-bit, level) for a codec; the ladder asks the platform's
  `findDecoderForFormat` by size only, as streaming always has.
