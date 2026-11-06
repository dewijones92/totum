---
title: Casting
kind: feature
area: playback
status: prototype
updated: 2026-11-06
---

# Casting

Cast (the button in the player's top bar) sends what is playing to a Google Cast device. Totum stays the brain: the TV
plays a stream the phone serves, so Totum's queue, autoplay, boost and position carry on as they do on the phone
([ADR 68](../adr/0068-cast-a-stream-the-phone-serves.md)).

- **Video:** YouTube's original video packets, relayed by the phone untouched (never re-encoded).
- **Audio:** decoded on the phone, through the same boost and (for talk, audio-only) the same silence skipping as local
  playback, re-encoded to AAC in 4 s segments. With skips on, Totum keeps episode time and moves on at the real end.
- **Speed** plays on the TV at the chosen rate.
- **Seeking** with the TV remote works; **next** on the TV remote moves Totum's queue (while the next item is waiting
  on the TV, its last 75 s); the TV moves to the next queue item by itself at the end of one.
- **Stop casting** hands back to the phone at the TV's position, picture and all.

- **Downloads** cast with their picture: the video track is copied (never re-encoded) into a seekable WebM or MP4
  once and cached.

- **Formats for the TV:** 4K VP9 and HEVC on the LG (H.264 only to 1080p, never AV1), 1080p on the Chromecast Ultra
  (measured); starting or stopping a cast switches the current video to the right stream. The TV is sent only the best
  picture it can show (ADR 122).
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

## The best picture, and stepping down (2026-11-06, [ADR 122](../adr/0122-a-cast-sends-the-best-rung-and-steps-down-itself.md))

Superseding the 2026-10-22 ladder: the LG picks one rung when it starts and never changes, so Totum sends only the
best the target can show (per-codec limits in `CastScreen`; a PC screen reports its own through the browser's media
capabilities, so Edge gets AV1 and 4K). After two stalls in 90 s Totum re-sends the item one height lower, at the same
place, and the strip says "⚠️ Dropped to 1080p · VP9, Wi-Fi too slow". The strip's quality line reads "📺 1440p · VP9 on
<TV name>" (`QualityLabel`). Logs: `the TV gets … only [… ceiling=…]`, `the TV stalled at …ms after playing`, `stepping
down from … to …p`, `sending …p instead of …`.

A home copy (`…/fetch/files?id=…`) is probed for a picture instead of dropped when its address names no file type; the
session line says `picture because …` for every item.

## The player stays on the phone (2026-10-22)

After a replay on the TV, Media3 could lose the playing item's name and the phone hid its player while the TV played
on (report 0.1.671). The phone now shows the item it sent last, so pause, seek and stop stay in reach. Listen-mode
casts and videos already at their best for the TV no longer replay. See ADR 68's amendment.


## It keeps your place and notices the end (2026-11-06)

Reports 20261101T174143 (finished on the TV, the app said 70%) and 20261101T205710 (TV off, the phone showed another
show). While casting, your place is saved every five seconds and on pause or seek, an end on the TV marks the item
played and moves the queue on, and stopping the cast resumes the item the TV was on at its place. Where the receiver
names nothing, Totum uses the item it last sent. The next item is put in line on the TV only in the last 75 s.
Cast progress reports the source's length, not the cut audio's. A spurious end (unknown length, about 0 ms, within 3 s
of a start) is ignored.
Logs: `[playback] saving <id> at …ms … under the id Totum sent`, `not saving the place: …`, `end of <id> counted (…)`,
`end of <id> not counted again …`, `ignored an end of <id>: … (state=…)`, `[cast] <id> left the receiver at …`,
`<a> -> <b> on the TV at …ms of …ms: it ended|skipped`, `hand-over now <id> at …ms (…)`,
`handed <id> over at …ms, the one kept while casting|the one Totum sent|the one the player has`, `[gapless] … getting
… in line (… cast=true)`.

## On the PC screen (2026-10-23)

🖥️ beside Cast plays on a PC instead of a TV: open the Pi bookmark `https://crashlog.333133333.xyz/screen` in Edge,
click once to allow sound, and the 🖥️ button appears on the phone (only while a screen page is open). The PC gets the same
stream as a TV, so skip-silence, boost, SponsorBlock and the queue behave as they do when casting; the phone is the
remote, and "📱 Back to this phone" takes it back. See [ADR 81](../adr/0081-the-pc-screen-is-a-page-the-phone-serves.md).

**Two phones (2026-11-06, [ADR 93](../adr/0093-the-private-area-opens-the-pc-screen-and-watch-here-takes-over.md)):** the Pi keeps one
entry per phone (install id, model, last heard, playing and title). `/screen` goes straight to the only fresh phone or
the only one playing; otherwise a chooser lists them, and the page's "🔀 Switch phone" link opens it.

**From the private area (2026-10-29, [ADR 93](../adr/0093-the-private-area-opens-the-pc-screen-and-watch-here-takes-over.md)):**
private area → **📱 Dewi's Totum** → the page says what the phone is playing ("▶️ On your phone: …", or "📭 Nothing
playing") → **▶️ Watch here** moves it to the PC with sound, or plays the queue's current item when nothing is loaded.
Nothing to tap on the phone. While the phone is charging on Wi-Fi it shows a quiet "🖥️ Ready for your PC" notice.
Logs: `[screen] Watch here pressed …`, `Watch here on screen …: moving what the phone has …` or `… the queue is asked …`,
`[queue] the PC screen's Watch here …: played=…`, `PC screen ready/not ready (charging=… wifi=…)`.

## On the home VPN (2026-10-31)

On `wg-home` the TV fetches through the home server instead of from the phone directly
(`https://<home server>/cast/c/<session>/…` → the Pi → the tunnel → the phone), at home or at anyone's house;
Totum does it by itself. Limited by the home upload speed. The 🔒 strip below is for any other VPN. ADR 68,
dot-files ADR 7.

## Even frame timing (2026-10-31)

The TV is given each frame at the film's own rate (1001 ticks of 24000 for 23.976 fps), not the source's
millisecond-rounded gaps (41/42 ms), which judder at 60 Hz. ADR 68.

## Torrents after a seek, and sound the phone can't decode (2026-10-31)

A streaming torrent that stalls after a seek no longer goes silent for the rest of the film: an early end is believed
only when it repeats at the same place for half a minute. A film whose sound the phone cannot decode (AC3 or DTS on
a phone without that decoder) now says so on the strip (🔇 pick another copy, one with AAC or MP3 sound) instead of
loading for ever. ADR 68.

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

