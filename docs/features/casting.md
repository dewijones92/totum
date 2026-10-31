---
title: Casting
kind: feature
area: playback
status: prototype
updated: 2026-10-31
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
- **Ordinary MP4s** (index at the end, not fragmented) cast with their picture too, repackaged the same way
  (2026-10-28).
- **Said before you pick it:** while casting, a torrent result whose name says XviD, DivX, AVI or AV1 carries
  "🔊 Sound only on the TV (XviD)", since no measured TV decodes those (`soundOnlyOnTheTvByName`, 2026-10-28).

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


## On the PC screen (2026-10-23)

🖥️ beside Cast plays on a PC instead of a TV: open the Pi bookmark `https://crashlog.333133333.xyz/screen` in Edge,
click once to allow sound, and the 🖥️ button appears on the phone (only while a screen page is open). The PC gets the same
stream as a TV, so skip-silence, boost, SponsorBlock and the queue behave as they do when casting; the phone is the
remote, and "📱 Back to this phone" takes it back. See [ADR 81](../adr/0081-the-pc-screen-is-a-page-the-phone-serves.md).

**From the private area (2026-10-29, [ADR 93](../adr/0093-the-private-area-opens-the-pc-screen-and-watch-here-takes-over.md)):**
private area → **📱 Dewi's Totum** → the page says what the phone is playing ("▶️ On your phone: …", or "📭 Nothing
playing") → **▶️ Watch here** moves it to the PC with sound, or plays the queue's current item when nothing is loaded.
Nothing to tap on the phone. While the phone is charging on Wi-Fi it shows a quiet "🖥️ Ready for your PC" notice.
Logs: `[screen] Watch here pressed …`, `Watch here on screen …: moving what the phone has …` or `… the queue is asked …`,
`[queue] the PC screen's Watch here …: played=…`, `PC screen ready/not ready (charging=… wifi=…)`.

## A VPN in the way (2026-10-23)

If a VPN on the phone carries your home network, the TV cannot reach the phone. The cast card now says so straight
away (🔒) instead of waiting for the TV to give up; turn the VPN off, or exclude Totum in the VPN app. ADR 68.

## Surround sound and downloads (2026-10-24)

A 5.1 film (The Apprentice, AAC 5.1) and a downloaded copy (Ali G Indahouse) both failed to cast: the first because
the TV's stereo stream was fed six channels, the second because the cast audio path could not open a local file.
Both fixed in ADR 68's consequences; the sound is mixed to stereo for the TV.

## A downloaded film starts at once, and the strip says what casting is doing (2026-10-31)

Report 20261031T070822-6de9304d (v0.1.778): casting the downloaded Matrix (2.4 GB HEVC MKV, 5.1) waited 31 s before
the TV got its manifest, and Dewi asked for *"a pop-up saying what it's doing, if it's repackaging"*. A downloaded file
was repackaged whole by ffmpeg (MKV to MP4, the picture untouched) before anything was served, after a WebM attempt
HEVC can never pass, while a torrent stream of the same film was already cut on demand by `MkvVideo`. Now both go
through one `VideoProbe` with a byte reader (HTTP or the file), so a downloaded MKV or MP4 is cut for the TV as it asks;
ffmpeg remains only for a file that cannot be cut (no Cues, laced or compressed tracks), MP4 first. On the laptop
stand-in a 150 MB 1080p HEVC MKV went from 882 ms to 104 ms for the manifest (read in 11 ms), which no longer grows
with the file, and no copy is written.

The cast strip now says what is happening: ⏳ Getting it ready for <TV>… (session made), 📦 Repackaging it for <TV>:
N% (the ffmpeg fallback, from ffmpeg's `-progress` file), ⏳ <TV> is starting it… (manifest served), and nothing once
the TV fetches its first segment (`CastStage`, through `CastStatus` session extras; logged `stage …`).

Judder: on the stand-in the stream's frames step 42/41 ms (millisecond MKV timestamps, under 1 ms off 41.7 ms, the
same before and after), so the stream is even; the stand-in itself dropped frames even for a light 720p AVC control on
a loaded laptop, so it cannot judge judder. The TV's 24 fps on 60 Hz cadence remains the suspect. A downloaded file's
video requests are now logged (sampled), so the next report shows whether the picture arrived late.

## The film's own subtitles on the TV (2026-10-31)

Report 20261031T100318-068abf73: the Matrix's English subtitles worked on the phone and not on the TV ("on the TV: none
of 0 offered"). They are a track inside the MKV, which the phone's player reads, while the cast server only offered
sidecar subtitle files. `EmbeddedSubtitles` lists a downloaded file's subtitle tracks with the bundled ffprobe, copies
each SRT track out with the bundled ffmpeg (stream copy: the build has no decoders or encoders) and converts it to
WebVTT in Kotlin, kept under `files/embedded-subs/`; image and ASS tracks are named in the log as not converted. A cast
of a downloaded file offers them; `EmbeddedSubtitleWarmer` prepares every downloaded torrent file in the background
30 s after launch and as downloads land, so a cast does not wait. A file not prepared yet starts it at the cast (logged
"saving them now for the next cast"). Checked on the stand-in: a test MKV with an English SRT track showed its cue.

