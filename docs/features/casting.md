---
title: Casting
kind: feature
area: playback
status: built — in use with the LG and the Chromecast Ultra (fixed from Dewi's diagnostics of 7 to 11 November, ADR 164); the TV volume, the read-ahead, the 12 s step-down, SubRip on the LG, the Pi's casting and casting from a friend's house, to a Chromecast or a PC screen (ADR 182, built 2026-11-27, PC screen 2026-11-28; the Pi side live since 2026-11-28) are not yet seen on a real TV
updated: 2026-11-28
---

# Casting

> **Status correction (2026-11-14).** The status said `prototype` (the index row still dated it 2026-10-21). It has since been used on the
> real LG and Chromecast Ultra and repaired from phone reports (20261031T100318, 20261101T174143, 20261101T205710 and the diagnostics
> of 7 to 11 November, [ADR 164](../adr/0164-casting-fixes-from-the-diagnostics-of-7-to-11-november.md)), so "prototype" understated
> it. Still **not seen on a real TV**, per this doc: the TV's volume (2026-11-06, "Not yet seen on a device"), the read-ahead on a
> throttled link, the 12 s step-down, SubRip captions on the LG, the PC screen's seek bar against a real phone (ADR 164's own
> "needs a real TV" list), the 24 fps on 60 Hz judder (still a suspect), a generic Chromecast taking H.264 and the 8 s start watchdog ([ADR 179](../adr/0179-a-silent-cast-moves-to-the-next-codec-and-an-unmeasured-tv-gets-h264-first.md), nothing run against a real generic Chromecast), and the Pi casting with the same code
> ([pi-plays-everywhere](pi-plays-everywhere.md): the relay is not yet seen on a real TV).

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

## The TV's volume (2026-11-06)

While casting, the phone's volume controls drive the TV. The strip shows "🔊 Volume 35% on <TV name>" (or "🔇 … is
muted") with a slider, the fullscreen volume swipe changes the TV and its readout says "📺 TV volume", and the hardware
keys reach the TV through the Media3 session's remote volume provider (no app code: the session's player is the cast
player, which reports a remote device). `CastVolume` is the state (`PlaybackState.castVolume`, 0 to 20 from the Cast
SDK), `PlaybackController.setCastVolume(fraction)` the one setter. Logs: `[cast] dewidebug TV volume asked at …: a -> b of
20`, `[gesture] begin VOLUME on the TV at …`. Not yet seen on a device.

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

## Resume rewind on a receiver that names nothing (2026-11-14)

Report 20261114T042830 (0.1.905, 11 Nov): tapping play after 30 s paused while casting to the LG crashed with
`IllegalArgumentException: MediaItemId must not be blank`. The receiver reports its item with no media id, and the
resume rewind (`ResumeRewindListener`) read that blank straight off the player while every other path already used the
stand-in. Now the rewind resolves the id through `namedId` (the item Totum last sent), skips with a line when nothing
names the item, and `ResumeRewind` and `carryOnIfWatchedElsewhere` refuse a blank id outright.
The 12 s step-down ([ADR 164](../adr/0164-casting-fixes-from-the-diagnostics-of-7-to-11-november.md)) had the same
gap: it read the blank id off the cast player, so on the LG a freeze was judged under `""`, found no ladder and never
stepped down. `CastStepDownWatch` now names the item through `namedItemId` (the receiver's id, else the item Totum sent,
which the service holds as the hand-over), and re-sends that item when the receiver's own copy has no id. `SilentTailWatch`
resolves its id the same way (it watches the phone's own player, which always names its items).
Logs: `dewidebug resume rewind skipped: nothing names the item [...]`, `dewidebug resume rewind skipped: the player has
an item but nothing names it [...]`, `dewidebug not asking where else it was watched: no item id [...]`.
`dewidebug step-down watch: the receiver names nothing; using <id>, the item Totum sent`, `dewidebug step-down watch: the TV is
buffering and nothing names the item, ...`, `dewidebug silent tail not watched: nothing names the item`.

## A generic Chromecast, and a TV that goes quiet (2026-11-20, [ADR 179](../adr/0179-a-silent-cast-moves-to-the-next-codec-and-an-unmeasured-tv-gets-h264-first.md))

Reports of 14 to 17 November (v0.1.927): Kitchen TV and Living Room TV (model string "Chromecast", not the Ultra) fetched the
manifest and the VP9 video's init and index and then nothing; audio-only casts worked.

- **H.264 first for a model `CastScreen.of` does not recognise** (`CastScreen.order`; the LG, the Ultra and a PC screen keep VP9
  or AV1 first). The phone's own ladder while casting follows the same order.
- **The cast ladder holds both codecs** (`VideoQuality.alternates`): without it the codec step had no other rung to move to.
- **Start watchdog.** The TV has fetched the picture and asked for no sound in 8 s (`TvStartWatch`): the item is re-sent at its
  place with the next codec (`CastStreamServer.stepDownCodec`, `CastStepDownWatch.tvDidNotStart`), at most twice, never when
  no other codec is offered. The strip says "📺 <TV> didn't start VP9, trying H.264". The refusal is held for the cast, and
  saved per model (`CodecRefusals`, `PrefsRefusalStore`) only when the TV then asks for sound from the other codec's session.
  Not armed for the Pi's targets.
- **Logs:** `dewidebug the receiver says playing|buffering|loading|idle because of an ERROR [item= loading= position=]`,
  `the receiver reported an error: type= detailedErrorCode= reason=` (the load's own result is not exposed by Media3, so it is
  read from these), `video request begun #n range= (k in flight)`, `video relay: N request(s) still in flight, the oldest for
  Xms` every 10 s while one is open, `the TV fetched the picture Nms ago and has asked for no sound since`, `… did not start
  <label>; trying <label> instead (set aside so far: …)`, `codec refusals saved: …`.
- **A route selected and no session callback in 5 s** (a Play Services Dynamite failure, report 20261114T200559): logged with the
  cast state and the Play Services version, and the snackbar "⚠️ Cast didn't start on <TV>" offers Retry (selects the route
  again). A Cast framework that will not start in the service, the app or the Cast button now logs why.

## Kid TV (2026-11-07)

A Library tile casts a chosen pinned playlist to a chosen TV by name in kid mode with one tap; see
[kid-tv-button](kid-tv-button.md) and [ADR 148](../adr/0148-kid-tv-is-kid-mode-on-the-tv-and-a-pinned-playlist.md).

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

- **The Pi casts with this same code** ([ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)): `CastStreamServer` in `:core:cast` decides and serves (rung choice, manifest, relayed video, processed-audio segments, captions, live, the sound-only fallback); the phone supplies MediaCodec (`MediaCodecAudioEngine`) and Media3, the Pi supplies ffmpeg (`FfmpegAudioEngine`). `CastStreams` in `:core:playback` is only the adapter from a `MediaItem`.

## Fixes from the diagnostics of 7 to 11 November (2026-11-13, [ADR 164](../adr/0164-casting-fixes-from-the-diagnostics-of-7-to-11-november.md))

- **Back 10 s, forward 30 s** on the TV and the PC screen too, as on the phone.
- **Stepping down** counts one starvation once, and a picture frozen for 12 s steps down by itself (also after a seek).
- **The relay reads ahead:** the next range is fetched while the TV plays the current one; on the phone the source is the
  playback cache. Log lines: `<id> video request #N range=… from=upstream|memory: first byte in …ms, …KB sent in …ms (… Mbit/s while
  transferring), aborted=no|yes[, SLOW]`, then `video relay, last 30s: …` summaries, `read-ahead of a-b …` notes.
- **A file whose track list is at its end** says so (toast on the phone, the cast strip on a TV) instead of staying silent.
  Log lines: `… still has no tracks the player can use …: 0 track(s): none; …`, `audio <label>: no audio track to cast: …`.
- **SubRip subtitle files** (a torrent's `.srt`) reach the TV. A streamed torrent file's own embedded tracks do not
  (`a streamed file's own subtitle tracks cannot be pulled out …`); a downloaded file's still do, and a Jellyfin item's now do
  (see the last section).
- **PC screen:** a seek bar with the time; back, forward, next and previous work from the phone; stale reports are counted
  (`stale reports in the last 30s: …`, `… sending load N to the page again`).
- **Failures say why:** `❌ <TV> couldn't start “<title>”: <reason>` after three failed manifests or sound segments in a
  minute (logs: `… failed (n of 3 before the user is told)`, `telling the user`). While casting, the phone no longer
  logs "the tail is not coming" for the TV's buffer.
- **Needs a real TV:** the read-ahead on a throttled link, the 12 s step-down, the SubRip captions on the LG, and the seek
  bar in Edge against a real phone.

## A relay that never waits on a locked cache span (2026-11-27, [ADR 186](../adr/0186-one-fetch-per-item-a-playing-stream-is-its-own-download.md) amended)

Reports 20261123T123853, 124440 and 171635 (0.1.944): a phone-direct cast hung for 28 to 90 s on a deep range at the resume position and the TV
gave up (`receiver error 321`). The relay read upstream through the playback cache's blocking data source (added with the tee on 20 November), which waits
without a timeout on a span another reader holds. The relay now has a data source of its own that reads around a locked span, and a 6 s
budget on the open as a second line of defence. Each open logs its wait, split into the cache's and upstream's first byte, and
anything over 3 s is a warning (`dewidebug relay open #N …`). Who holds a locked span cannot be known (the cache does not say). Tests:
`ARelayReadWaitsForNoLockedSpanTest` (instrumented), `BudgetedOpenTest`. Not seen on a real TV: whether the resume-position cast now starts.

## A crossover with nothing casting is quiet (2026-11-27, [ADR 164](../adr/0164-casting-fixes-from-the-diagnostics-of-7-to-11-november.md) amended)

`RemoteEndWatch` no longer logs "Totum never saw X play there" for a phone gapless crossover while nothing casts.

## At a friend's house: home items come from the home server (2026-11-27, [ADR 182](../adr/0182-a-friends-chromecast-fetches-home-items-from-the-home-server-by-a-session-link.md))

Cast to a friend's Chromecast from the Cast button, as anywhere: the phone finds it on the friend's Wi-Fi and drives it. What changes is
**where the stream comes from** for an item only home can serve (a Jellyfin film, a torrent at home, a 🏠 home copy), when the phone is
**not on the home network**: the home server builds the stream with the same brain it uses for its own TVs and the Chromecast fetches
it from `https://totum.<home>/cast-out/<link>/manifest.mpd` over the internet, so the phone relays nothing.

- **The link** is a random 256-bit secret the home server minted for that one cast, valid six hours, ended when the cast ends (the Cast
  session closing, or the same item being cast again). It reaches that session's files and nothing else, and it is the one exception
  to "no link carries a token" ([ADR 90](../adr/0090-a-token-per-device-and-none-in-links.md)); the device token still gates the minting.
- **Which route** is decided per item and logged with its reason (`cast-out` lines): a YouTube stream, a podcast, a file on the phone, or
  anything that does not come from the home server stays phone-direct; on the home network (the Pi answers on its own LAN address) the
  phone serves it as before; signed out, an unknown home network, a home server that refuses or does not answer within 7 s: phone-direct,
  with the reason. There is no setting.
- **Left off on this route:** skip silence (the phone could not map the TV's clock back), live streams. The boost applies.
- **Setup** is on the Pi, by hand: the nginx patch and `TOTUM_CAST_PUBLIC_BASE`, in `tools/handoff/cast-out/README.md`. Until then the home
  server answers "not set up" and every cast is phone-direct, as before.
- **Logs to read in a report:** `[cast-out]` lines on the phone say which route and why, the session (six characters), the model and the
  expiry; on the Pi they say what each session was asked for, how many bytes it served, why it ended, and every refusal (the first few,
  then every fiftieth).

### How Dewi tests it away from home

The home Wi-Fi plus mobile data is not enough: a Chromecast has to be on the network the phone is on, and a phone on mobile data cannot see
one. The simplest stand-in is a second phone's hotspot:

1. Make the hotspot on a second phone (mobile data on). Put a Chromecast on it (set it up from the Google Home app on the hotspot) and join
   Dewi's phone to the same hotspot. That network has no route to `192.168.0.x`, which is what "away" means here.
2. On Dewi's phone open Totum and play a Jellyfin film or a 🏠 home copy; tap Cast and pick the Chromecast.
3. In Totum's diagnostics (or `adb logcat -s dewidebug | grep cast-out`) look for: `home network: asked 192.168.0.x:8771/lan-check ... so the
   phone is NOT on the home network`, then `the friend's Chromecast fetches it from the home server (session xxxxxx…)`. The Chromecast
   should start within a few seconds; seek with the Totum bar.
4. Stop casting and check the Pi's log: `session xxxxxx ended: the phone ended the cast; N request(s), … KB served`. The link itself is
   never shown whole, so ending is proved by that line; `tools/cast-bench/standin/public-standin.sh` does the mint, the play and the end
   with the 404 check, against a local `totum serve`.
5. For the opposite check, join the home Wi-Fi: the same film should log `the phone is on the home network, where it serves home items as
   before` and cast phone-direct.

Not seen on a real Chromecast or the real Pi. Seen: the real `totum serve`, a real nginx from the patch, and Shaka in Edge
(`tools/cast-bench/standin/public-standin.sh`).

### A PC screen at a friend's house (2026-11-28, [ADR 182](../adr/0182-a-friends-chromecast-fetches-home-items-from-the-home-server-by-a-session-link.md) amended)

The PC screen (🖥️, or **▶️ Watch here** on the page) takes the same route as the friend's Chromecast: when the phone is not on the home network and the
item is home-only (a Jellyfin film, a torrent at home, a 🏠 home copy), the page plays it from the home server's public link and the phone relays
nothing. The decision is the same code (`PublicCastRoute.routedStream`), with the same reasons and the same phone-direct fallback; the `load` the phone
sends the page then carries `"direct":true`, so the page hands Shaka the link as given instead of rewriting it onto the phone's address. The page is still
served by the phone and found through the Pi bookmark, so the PC must be on the same network as the phone; only the media comes from the Pi.

- **Same limits as the Chromecast:** skip silence off, YouTube and live stay phone-direct, the home network keeps the phone's own address, no setting.
  No model is sent for a PC, so the Pi builds a VP9/H.264 stream, H.264 first, which every browser plays.
- **Logs:** `[cast-out] … the PC's browser fetches it from the home server (session xxxxxx…, model unknown, …)` or `… the phone serves it to the PC's
  browser, because …`; `[screen] <id>: load N sent to the screen from the home server's public link` (or `from this phone`).
- **How Dewi tests it:** the hotspot setup above, with a PC on the same hotspot as the phone. On the PC open the Pi bookmark
  (`https://crashlog.333133333.xyz/screen`), which goes to the phone's page on the hotspot; click ▶️ Watch here with a Jellyfin film or 🏠 home copy
  loaded on the phone. In the phone's diagnostics look for `home network: … NOT on the home network`, then `the PC's browser fetches it from the home
  server`, then `load 1 sent to the screen from the home server's public link`. The page's status line says `▶️ <title>`; the Pi's log shows the
  session's `GET manifest.mpd`, segment lines, and `ended: the phone ended the cast` after 📱 Back to this phone.
- Seen: the real page against the real gate, a stand-in for the phone's page server and headless Edge (`tools/cast-bench/screen/public-screen.sh`; with
  `MODE=phone` it shows the old rewrite failing with Shaka error 1001). Not seen on a real phone or PC, or over the real internet.

## Captions from a cast the Pi makes (2026-11-27, [ADR 191](../adr/0191-casts-from-the-pi-carry-captions-and-a-speakers-bluetooth-link-is-measured.md))

The Pi's cast of a YouTube item carries the same resolved caption tracks as a phone-direct cast (`TvSubtitles`, `CaptionPick`), under the
deck's own caption choice (on in English until switched off). The remote card shows a Captions tile only while the receiver lists tracks. Not seen on
the LG: whether the receiver reports the manifest's text tracks to the helper.

## Waking the LG from deep standby, and what a target says when it cannot be reached (2026-11-28, [ADR 177](../adr/0177-a-device-says-how-it-comes-on-and-goes-off-lg-over-webos-kodi-started-over-adb.md) amended)

Run on the real LG and the Fire Stick on 28 November: wake-on-LAN did not wake the LG from deep standby, and the Fire Stick's
sleep-then-wake did (the stick tells the TV over HDMI-CEC). A paired LG is now on only while webOS says `Active`, so its
dot no longer flaps with the cast port; a TV with a stick shown on it is nudged over CEC through that stick when wake-on-LAN
got no answer; a volume set on a target with no link is refused in words, not logged as set; and a Kodi that refuses its
sign-in says so. The Pi's logs carry what webOS and the cast port answered at every change and each step of a wake. Fixed
against the fakes; not yet run on the real TV.

## A video YouTube offers only as HLS (2026-11-28, [ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md) amended)

When YouTube offers a video only as `manifest.googlevideo.com/…/hls_playlist/…` (muxed MPEG-TS, no stream with its own audio), the
cast is no longer handed that address: it failed on the LG at 03:43 on 28 November with `idle (ERROR) after 0ms`, because a
receiver cannot fetch googlevideo (no CORS). The one stream server now answers `…/c/<token>/relay.m3u8` with content type
`application/x-mpegURL`, reads YouTube's playlist on every request and rewrites each address in it to a name on itself, then streams
each part from YouTube with its `Range`. The phone's converter and the Pi's `ChromecastPlayer` share the same content-type rule
(`CastFormat.mimeOf`). Picture and sound are YouTube's own, so silence cutting and the boost do not apply and a sound-only request
still carries the picture. A third party's HLS and a googlevideo DASH manifest are unchanged. The Pi log reads `YouTube offers only an
HLS manifest … Totum serves it and relays every playlist, segment and key itself`, then one `HLS playlist … rewritten` line per
playlist read. Tests: `CastStreamServerHlsRelayTest`, `ChromecastHlsOnlyTest`. Fixed against a fake upstream only; not yet run on the real LG.

## Three real-device fixes of 28 November (2026-11-28, [ADR 164](../adr/0164-casting-fixes-from-the-diagnostics-of-7-to-11-november.md) and [ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md) amended)

Pi `totum serve` v0.1.984 on the LG and Kodi, 28 November. All three are in `:core:cast` or the Pi's wiring of it, so the phone's cast gets what is shared.

- **An MKV with its Tracks beyond the first 64 KB** (Spinal Tap II from Jellyfin, 5.5 GB, played sound only). The MKV reader now follows the
  SeekHead and fetches `Tracks` (and `Info`) with a `Range` request when they are not in the head, for any source that answers ranges (Jellyfin
  through the Pi, TorrServer, a plain HTTP file). Still refused, with the reason, when there is no SeekHead to say where they are or the source
  will not give the range. Logs: `MKV SeekHead (segment data starts at …) names Info at …, Tracks at …, Cues at …; the head holds 0x…`,
  `MKV Tracks are not in the first 65536 bytes; the SeekHead names them at A, so bytes A-B (N bytes) were fetched by range in Mms`, and on
  refusal `the TV gets sound only — its video is in a format Totum cannot read for a TV (MKV Tracks are not in its first 65536 bytes and …)`.
  Closes the "track list at its end" gap for ranged sources (see the 7 to 11 November section: that toast/strip message now appears only for the
  two refusals above). Tests: `MkvTracksBeyondTheHeadTest`, `CastStreamServerMkvTracksTest`.
- **Captions on the relayed YouTube HLS** (3Uavv96zDkM had eight resolved tracks and the TV was offered none). The rewritten master playlist now
  carries them as an `EXT-X-MEDIA:TYPE=SUBTITLES` group, each track a one-segment WebVTT playlist served by the relay (`subtitles/N.m3u8`,
  `subtitles/N.vtt`); the phone's relayed cast takes the same path. Logs: `HLS master now carries N caption track(s) as an EXT-X-MEDIA
  TYPE=SUBTITLES group …`, `HLS subtitle playlist N …`, `subtitles <lang> for <id>: upstream 200`. On the real LG the Pi's log should then
  say `the receiver offers N caption track(s) […]` and `captions, asked for en: …`. Test: `HlsRelayCaptionsTest`.
- **A Jellyfin item's own subtitle tracks** (#188, partly). The Pi asks Jellyfin for the item's media streams, offers each text subtitle stream
  (a picture-based one such as PGS is named in the log and skipped) and the cast server serves it as `subtitles/N.vtt` after fetching Jellyfin's
  own WebVTT conversion (`Videos/{id}/{source}/Subtitles/{index}/Stream.vtt`, read by the Pi with its key, never given to the TV). **Torrents are
  left as they were:** a torrent's streamed MKV tracks cannot be pulled out without reading all of it, and its sidecar `.srt` files still reach the TV.
  Not done: the phone's own cast of a Jellyfin item, and the friend's-house route. Logs: `subtitles for <id>: N subtitle stream(s) in source …:
  [#2 en subrip text, #4 fr hdmv_pgs_subtitle picture, skipped]; offering K to the TV`, `<id>: K subtitle track(s) from Jellyfin for the TV [en, …]`.
  Tests: `JellyfinSubtitlesTest`, `TargetStreamsJellyfinSubtitlesTest`.
- **Kodi's empty request** (found 06:22 on Kodi 21.3, no inputstream.adaptive): the relay's 404 for a name it never listed (the bare session
  address) counted towards the "3 failures" notice. It no longer does, and is logged once with the exact request path
  (`HLS relay was asked for "<path>" …`). Test: `HlsRelayUnlistedNameTest`.

Not seen on a real TV: all four (fixed against fakes, a hand-made MKV and a fake Jellyfin).

