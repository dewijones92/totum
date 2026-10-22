---
title: Casting
kind: feature
area: playback
status: prototype
updated: 2026-10-22
---

# Casting

Cast (the button in the player's top bar) sends what is playing to a Google Cast device. Totum stays the brain: the TV
plays a stream the phone serves, so Totum's queue, autoplay, boost and position carry on as they do on the phone
([ADR 68](../adr/0068-cast-a-stream-the-phone-serves.md)).

- **Video:** YouTube's original video packets, relayed by the phone untouched (never re-encoded).
- **Audio:** decoded on the phone, through the same boost and (for talk, audio-only) the same silence skipping as local
  playback, re-encoded to AAC in 4 s segments. With skips on, Totum keeps episode time and moves on at the real end.
- **Speed** plays on the TV at the chosen rate.
- **Seeking** with the TV remote works; **next** on the TV remote moves Totum's queue; the TV moves to the next queue
  item by itself at the end of one.
- **Stop casting** hands back to the phone at the TV's position, picture and all.

- **Downloads** cast with their picture: the video track is copied (never re-encoded) into a seekable WebM or MP4
  once and cached.

- **Formats for the TV:** 4K VP9 on the LG, 1080p VP9 on the Chromecast Ultra, HEVC on both (measured); starting or
  stopping a cast switches the current video to the right stream.
- **You can see you are casting:** "📺 Casting to <device>" across the mini player and the full player, with the Cast
  button in it.
- **Sound only, said out loud:** a video the TV cannot show gets Totum's processed sound, and the strip
  says "⚠️ <device> can't show this video (AV1 1080p), so it plays the sound only".
- **Subtitles** on the TV come from the player's usual Subtitles button; its menu says they show on the TV.
- **Android 17:** the first Cast tap asks for local network access ("Nearby devices").

- **Torrents while streaming** cast with their picture: the MKV is repackaged into MP4 pieces as the TV asks, nothing
  re-encoded (H.264 and HEVC).

- **Live streams** cast: the phone relays the live segments (the TV cannot fetch them itself); sound as YouTube
  sends it, without Totum's boost yet ([ADR 69](../adr/0069-live-streams-play-a-manifest-totum-builds.md)).

Not yet: Totum's boost on live sound while casting; an opt-in re-encode for a video the TV cannot show; YouTube subtitle fetches for the TV (and for
the phone's own player) going through the politeness gate.

Bench: `tools/cast-bench/` (README there). With no TV free, `tools/cast-bench/standin/standin.sh` plays what Totum
would cast in Shaka Player in a private headless Edge and times start, seeks and subtitles.

Diagnostics: every decision logs under `[cast]` (`dewidebug`): the manifest URL and what it carries, each request the
TV makes, audio production from which segment and why, the boost changing mid-cast, the receiver dropping an item and
whether that was its end, `cast status: device=… refused=…`, `the TV gets sound only — its video is …`, `subtitles on
the TV: …`, each producer restart's `source opened/was waiting in`, `seeked to … skipped N packet(s)` and per-stage
`ms spent`, each video range's `upstream 206 in Nms`, `back on the phone 5000ms: rendered=…` after a cast, and `video frames in the last 30s` while
a video plays locally.


## Reaching the TV, and knowing you are casting (2026-10-22)

- **Which address the TV is given.** The phone advertises its Wi-Fi (else Ethernet) address, never a VPN's. A VPN
  network on Android carries the Wi-Fi transport too, and report 20261022T115247 (0.1.662, "casting stuck???") shows the
  TV handed `10.13.13.2`, the phone's WireGuard address, which it can never reach: it fetched nothing and the cast sat
  still. `CastAddress.choose` (pure, `CastAddressTest`) decides; every candidate network is logged.
- **When the TV fetches nothing.** If the TV has asked the phone for nothing 12 s after a hand-over (`CastReach`), the
  casting strip says so (`CastProblem.TV_NOT_FETCHING`), and with no usable address at all it says that instead
  (`NO_ADDRESS`). The first request of each cast is logged with how long it took to arrive.
- **Casting is visible everywhere.** While a cast session is on, the Cast button sits beside 🔍 in every screen's
  header (`CastingAction` in `TitleAndActions`), so stopping a cast never needs the player open.

## A ladder, not one quality (2026-10-22)

A YouTube video reaches the TV as up to three qualities in one codec family, and the TV switches between them itself:
no reload when the phone was playing something lower than the TV can take. The casting strip shows the quality the TV
is fetching, and "going up to …" while it climbs. When the phone's own stream is one the TV cannot decode (AV1 on the
Ultra), the TV gets its own formats from the first load. See ADR 68's amendments.

## The player stays on the phone (2026-10-22)

After a replay on the TV, Media3 could lose the playing item's name and the phone hid its player while the TV played
on (report 0.1.671). The phone now shows the item it sent last, so pause, seek and stop stay in reach. Listen-mode
casts and videos already at their best for the TV no longer replay. See ADR 68's amendment.

