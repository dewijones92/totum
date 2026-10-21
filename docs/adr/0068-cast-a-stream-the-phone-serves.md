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
- The service holds a wake lock and a Wi-Fi lock while it serves, so a cast survives the phone being locked; leaving the
  cast lets go of the video surface so the phone's picture comes back (`PictureWatch` logs the frames that prove it).
- Debug builds read `files/cast-base-url.txt` for the address to advertise (the emulator bench reaches the TVs through an
  ssh tunnel on the Pi); release builds advertise the phone's Wi-Fi address.

## Consequences

- The phone relays the video bytes over Wi-Fi and encodes the audio while casting; the TV decodes.
- Not yet: skip silences while casting (it changes the timeline), speed on the TV, a downloaded video's picture (cast as
  its sound for now), formats chosen for the TV rather than the phone, live streams (given to the receiver as they are),
  a warning and opt-in re-encode for a format the TV cannot play, and a TV-side up-next UI (Dewi chose the free Cast
  queue for now).
- Seeking with the TV remote restarts the audio producer at the new segment; expect a second or two of buffering.
