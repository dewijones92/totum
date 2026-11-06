---
title: A cast sends the best picture the target can show, and steps down itself
kind: adr
status: accepted
updated: 2026-11-06
---

# 122. A cast sends the best picture the target can show, and steps down itself

- Status: Accepted (amends [ADR 68](0068-cast-a-stream-the-phone-serves.md), which offered a ladder of up to three rungs)
- Date: 2026-11-06

## Context

Dewi, 2026-11-05: "best quality please", for every cast target including the PC screen, 4K and AV1 where the receiver
can show them. Proofs on the LG OLED65C24LA the same day: it picks one rung when the stream starts and never changes
(throttled to 2 Mbit/s it stayed on 1440p and stalled four times in 75 s); sent only the best rung, 1440p and 2160p
VP9 and HEVC 2160p played; AV1 errors at any size; H.264 errors above 1080p. So a ladder buys nothing on the LG, and
the receiver's own adaptation cannot be relied on.

## Decision

- **The TV is sent one rung: the best it can show.** `CastLadder.ranked` orders what the phone offers by height, then
  by the target's codec preference; `CastRungs.choose` reads the top rung's header (first the best, the whole top
  height only when a codec was unknown) and keeps the first the target can really show. Fewer probes than the ladder
  needed (it read up to six). When no offered rung is readable the phone's own flowing URL is probed as before; the
  rung's URL, not the phone's, is what the manifest relays.
- **`CastScreen` is the target profile.** It gains per-codec maximum heights (`codecHeights`) and one ranking
  (`preference`). The LG (webOS, OLED/QNED/NANO 2020 on): VP9 and HEVC to 2160p, H.264 to 1080p only, never AV1; the
  Chromecast Ultra as measured in ADR 68: VP9, HEVC and H.264 to 1080p, no AV1. A PC screen reports its own: the page
  asks `MediaCapabilities.decodingInfo` for each codec at 2160, 1440, 1080 and 720 and says `VP9:2160+AV1:2160+AVC:1080`
  in its hello (`screenProfile` parses it; a bare `VP9` keeps the screen's own height), so Edge gets AV1 and 4K.
  `VideoCodecSupport.preference` is the one ranking the phone's own quality ladder and the cast ladder both use;
  `CastAwareCodecSupport` takes the target's.
- **After two stalls within 90 s Totum steps down itself.** `CastStepDownWatch` listens to the cast player (and the
  PC screen's player): a buffering that begins after the item played, while it is not paused and not within 4 s of
  a seek, pause or item change, counts (`StallsBeforeStepDown`). The second asks `CastStreams.stepDown`, which lowers
  that item's ceiling to the next lower offered height and re-sends the queue at the same item and position
  (`setMediaItems`), so the TV loads a manifest with the lower rung. The strip says
  "⚠️ Dropped to 1080p · VP9, Wi-Fi too slow". An item with no lower offered height (a file, a torrent) keeps going and
  logs why. The ceiling lasts until another item is cast or the cast ends.
- **One label.** `QualityLabel(height, codecFamily)` in `:core:domain` writes "1440p · VP9" (height first); the cast
  strip ("📺 1440p · VP9 on <TV name>"), refusals and every cast log line use it. The player's quality menu and the
  download chooser follow in the codec-everywhere item.
- **A Pi copy casts with its picture.** An address that names no file type (`…/fetch/files?id=…`) is probed, not
  dropped: `pictureVerdict` says why for every address and the session line reads
  `picture because …`. See docs/todos/cast-home-copy-picture.md.

## Consequences

- The "going up to …" strip line and `castFetchHeight` can no longer fire (one rung); left for a clean-up.
- A step-down reloads the TV at the same place: expect a brief re-buffer, and `RemoteEnd`/progress logic seeing a
  playlist change (the cast progress item must treat it as no end).
- A phone that casts a ladder-less item (a file, a torrent) has nothing to step down to; the TV's own adaptation is gone
  with the ladder, so a slow link there is a stall, not a lower picture.
- Tests to add in the testing stretch: `CastScreenTest` (per-codec caps, `preference`, label), `QualityLabelTest`,
  `CastLadderTest`/`CastRungsTest` for the ceiling and `nextLowerHeight`, `StallsBeforeStepDownTest` (second stall in
  the window, a stall outside it), `screenProfile` with `VP9:2160` tokens, and `pictureVerdict` for `files?id=`.
