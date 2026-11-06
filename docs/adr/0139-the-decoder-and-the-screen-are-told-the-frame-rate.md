---
title: The decoder and the screen are told the frame rate
kind: adr
status: accepted
updated: 2026-11-06
---

# 139. The decoder and the screen are told the frame rate

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06, on his Pixel 7: *"I'm running the video at 2x speed and it just doesn't look smooth … make it smooth …
my priority."* Measured on that phone (0.1.870, a 60fps AV1 video, hardware decoder `c2.google.av1.decoder`):

- at 2x the screen showed **8.5 frames a second**; the player's own counter said 271 rendered and 3131 dropped late in
  30 s. Skip silences off made no difference, and the playback thread was 22-36% busy, so neither was the cause;
- a Perfetto trace showed the decoder finishing only ~62 frames a second (`WaitFinishedPacket` ~15 ms each) while
  nothing was busy, so the hardware ran at a low clock. The vendor tables give this decoder 219-252 fps at 1080p;
- at 1x the picture alternated 11 ms and 22 ms: 60fps on a 90Hz panel.

The cause is one missing fact. YouTube's video-only streams (fragmented MP4 and WebM) state no frame rate, and Media3's
extractors do not work one out, so `Format.frameRate` is unset. Media3 sets the codec's operating rate (frame rate ×
speed) and the surface's frame rate only from that field, so the decoder was configured with neither. Giving the rate
after configuration (a first attempt, stamping it on the 12th frame) changed nothing: this decoder takes its clock from
the configuration.

## Decision

- **Every extractor is wrapped** (`StampsFrameRate`, in `totumExtractors()`, so YouTube, video podcasts, torrents and
  files on the phone alike). A video track whose container gives no frame rate has its format and its first 12 frames
  held back; the rate is worked out from their times (median spacing, snapped to the common rates) and the format goes
  to the player carrying it, before the decoder is configured. The frames follow unchanged. A stream that ends first,
  or uneven times, sends the format without a rate; a seek before judging drops the held frames.
- **The live manifest states each format's frame rate** (`frameRate` on its `Representation`), from YouTube's `fps`.
- **Every video surface is one `VideoSurface`** (player, picture-in-picture, Shorts reel, kid lock), which asks the
  window for the display mode that shows the frames evenly: the fastest mode the content rate (frame rate × speed)
  divides into, else the fastest that divides into it, else none. 60fps at 1x or 2x asks for 60Hz on the Pixel 7;
  30fps at 1x keeps 90Hz; 25fps at 2x leaves it to the system. Media3's own surface hint only switches when the change
  is seamless, which 90→60 on this panel is not.

## Consequences

- Measured after, same phone and video: at 2x, 90.1 fps shown, every frame 11.1 ms apart, 0 dropped late in 30 s
  (2340 rendered); also when the video started at 1x and was raised to 2x mid-play. With Skip silences on, the only
  drops are the frames inside each cut silence (the picture jumps past them, as it should).
- Start-up holds ~0.2 s of video frames before the decoder sees them; they are already downloaded.
- A report says `[video] frame rate N fps worked out from 12 sample times … before the decoder was configured` and
  `[video] screen refresh preference …`, so a phone report can show both happened.
- The DASH and HLS chunk paths (the live manifest aside) still rely on their manifests for the rate.
