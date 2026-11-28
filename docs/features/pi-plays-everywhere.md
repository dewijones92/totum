---
title: 📡 The Pi plays to any TV, speaker or browser; the phone is a remote
kind: feature
area: casting/pi
status: built 2026-11-07, Batch A (tests written 2026-11-07; volume rule and the YouTube relay fixed after the first run on the LG; the relay not yet seen on a real TV)
updated: 2026-11-28
---

# 📡 The Pi plays to any TV, speaker or browser

The Pi holds one queue per target and plays it; the phone shows and controls those queues. Decisions:
[ADR 152](../adr/0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (targets, queues, send-to,
colours, who reports to YouTube) and [ADR 153](../adr/0153-the-pi-runs-the-phones-sound-processing.md) (the phone's own
sound processing on the Pi). Brief: [pi-plays-everywhere.md](../todos/pi-plays-everywhere.md).

## The selected device's bar and the remote page (ADR 180, 2026-11-20)

- **A bar for the chosen device on every tab.** While the device picked in Play on… has something to show, the dock's bar is that device's,
  not the phone's mini player: its art, `📺 LG TV · ▶️ Playing`, the title, back 10, a filled play/pause, forward 30 and skip, tinted and
  bordered in the device's colour. A tap opens its remote page. An idle chosen device leaves the phone's own bar alone.
- **The remote page is one scrolling list.** A compact card first (a heading that names the link state, the title, a seek bar with elapsed
  and total at its ends, previous, back 10, play/pause, forward 30, next, volume with its owner and an info tap for the reason; Stop is an
  icon at the heading's end), then a "⚙️ Controls" row (Repeat, Sleep, Skip silences, Boost, Colour, all the player's own tiles), then
  "📋 Up next on LG TV · 54" and the queue.
- **Chips carry a connection dot** (linked, ready, waking, reconnecting, asleep, problem), hint at more chips off the edge, wear a glyph
  per kind (📱 🔈 📺 📽️ 🖥️), and a long-press opens the colour choices (48 dp swatches).

## The lock screen and the notification shade (ADR 188, 2026-11-20)

- **A second media session beside the phone's.** While the dock bar is the device's (`barDecision`: chosen, listed, something current),
  `PlaybackService` also holds a `totum-remote-target` session fronted by `RemoteTargetPlayer`, with its own notification (id 1002). The
  phone's session, notification (1001), Exsurge's Live Update and the foreground rules are not touched, and the app's own controller never
  sees it. It goes when the phone is chosen or the device has nothing left.
- **One card at a time.** The phone's own card shows when no device is shown, or when only the phone is playing; otherwise the device's
  does (a tie goes to the chosen device), and the other's notification is withdrawn, so the shade never holds two sets of controls
  (`NotificationCards`, `PlaybackService.onUpdateNotification`). Each move is logged with its reason.
- **It shows** the title and art (through the spoiler shield), `📺 LG TV · ▶️ Playing · author` as the artist line, the device's name as the
  notification's sub-text and its colour as the notification colour, and the position moving between reads.
- **It controls** play and pause, back 10, forward 30 and next, as the same `TargetAction`s the dock bar sends. No back or forward on a live
  item.
- **It reads the Pi with the screen off** every 10 s while the device plays and every 30 s while it is paused, never when the screen's own
  read is fresh (`RemoteSessionBridge`, `TargetsRepository.refreshIfOlderThan`).
- Tapping the card opens the app, not yet the device's remote page.

## One set of controls, boost under a picture, what each device is doing, and a measured link (ADRs 180 and 191, amended 2026-11-27)

- **No bar over the page.** On the Queue tab the chosen device's remote card already carries its transport, so the dock draws neither the
  device's bar nor the phone's mini player there (`dockBarFor`). On every other tab nothing changes: a tap on the bar opens that device's page.
- **The card's tiles are the phone's, in the device's colour.** Repeat, Sleep, Skip silences, Boost, Captions (when the receiver lists
  tracks) and Colour. Not offered, and why: playback speed (no speed stage in the Pi's sound chain or the Chromecast's stream), Listen/Watch
  (the device's kind decides), Explore and history (phone queue features).
- **Boost under a picture.** A TV showing a picture is offered Boost (the Pi re-encodes the audio for it anyway) but not Skip silences.
- **Chips and the Play on picker say what the device is doing:** `▶️ Toy Story`, `⏸️ Toy Story`, `⏳ Toy Story`, `⚪ Idle`, a second line that wraps.
- **A soundbar has a seek bar** with Skip silences or Boost on: the Pi reports the source's length (ffprobe).
- **A wake that failed no longer outlives the start that played.** The static ARP entry is a unit (`server/systemd/totum-tv-arp.service`).
- **A speaker's Bluetooth link is logged** (RSSI, link quality, underruns, reconnects, drops) once per 30 s while it plays, from a host
  collector (`server/systemd/totum-bt-link.service`).

## What it does

- **Queue tab, chips on top.** 📱 This phone, then a chip for each target that has a queue (coloured, with its kind's
  emoji: 🔈 speaker, 📺 TV or Chromecast, 🖥️ browser), and ➕ Play on… for targets with nothing queued (a sleeping one is woken when picked, and 🖥️ Add a browser… is in it). The chips show
  once the home server lists at least one target.
- **A target's queue is the same list.** The same `MediaItemRow`, group headings, jump (tap), remove (menu) as the
  phone's own queue; above it what plays, the position, ⏮ ⏯ ⏭ ⏹️, a volume slider, ✂️ Skip silences and 🔊 Boost (the
  phone's own processing, run on the Pi), and 🎨 Colour. Each row says "➕ Added by …" when the phone that added it is known.
- **Where taps go.** While a remote chip is selected, Play, Add to queue, Play next, Shuffle all and the rest queue on
  that target, not on the phone. 📱 This phone puts everything back, and so does leaving the Queue tab. Kid TV, kid-locked
  shortcuts and the Shorts reel always play on the phone. Every send shows in the progress strip
  ("📡 Sending to …", then "✅ Sent 12 items to …"); if the Pi refuses, cannot be reached or cannot start, the phone
  plays the items itself and the strip says why.
- **📡 Send to… in the Queue header** moves what plays: the playing item and the rest of its group, at the second it was
  at, inserted after what plays on the target (the target's other items stay). The phone pauses and drops them once the
  target says it is playing; if the target does not start, the phone keeps its queue and the strip says why.
- **Speakers are targets.** Any paired Bluetooth device with an Audio Sink is listed, linked only while it plays
  (ADR 116, unchanged), each with its own queue at the same time as the others.
- **DLNA renderers and AirPlay receivers** are the same target with another control ([ADR 174](../adr/0174-dlna-and-airplay-are-more-controls-behind-the-one-cast-player.md)): `TOTUM_DLNA_TARGETS=Kodi=http://192.168.0.211:1285/` and `TOTUM_AIRPLAY_TARGETS=LG TV=192.168.0.219` (pair once: `totum airplay-pair "LG TV"`); they are given the same processed sound as an HLS playlist (`hls.m3u8`), and appear as TVs. A name in two lists is one device that uses the first protocol that answers.
  **Since [ADR 175](../adr/0175-dlna-airplay-and-kodi-carry-the-picture-over-the-one-stream-servers-hls.md) a video has its picture too**: `hls.m3u8` is then a master playlist of the picture (the original, cut by range or remuxed, H.264 or HEVC in MP4, never VP9, WebM or AV1) and the processed sound, from the very same session as the Chromecast's DASH manifest; a device whose DLNA sink lists no video, or that cannot decode the codec, plays the sound and the strip says why.
- **Kodi** (the Fire Stick) is driven by its own JSON-RPC API (`TOTUM_KODI_TARGETS=Kodi=192.168.0.211:8080`, sign-in read from the 600 file named by `TOTUM_KODI_CREDENTIALS`: `user=` and `password=` lines, kept under the Pi's data folder, never in a link, a log or the environment itself): `Player.Open` of the brain's HLS, `Player.PlayPause`, `Player.Seek`, `Player.Stop`, `Player.GetProperties` for time and speed, and `Application.SetVolume` for the volume slider only. The same name in `TOTUM_DLNA_TARGETS` is the same device; Kodi's JSON-RPC is tried before its DLNA renderer.
- **TVs and Chromecasts** listed in `TOTUM_CAST_TARGETS` (name=address, optionally `@mac`, `;`-separated) are driven through `pychromecast`.
  **A TV loads what the phone would load** ([ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)):
  `…/c/<token>/manifest.mpd` (`application/dash+xml`, stream type buffered) served by the same `CastStreamServer` as the
  phone's. A YouTube video is the phone's inputs (the chosen video stream, its own audio, the ladder), so the TV gets the
  best picture it can show, relayed untouched, and the sound as processed AAC segments (the Pi's ffmpeg and `PcmChain`);
  a home copy, a Jellyfin film, a podcast and a file on the Pi are cast as the file or address they are; a TV that cannot
  show the video gets sound only and the target says why. A live stream gets `live.mpd` with its segments relayed by the
  Pi. Seeking works on a processed item, and with silences cut (sound only) the position is the source's. A stream the
  core declines (a single muxed YouTube stream, a third party's manifest) is given as it is, through `/y/<token>` (2 MB
  ranges, because YouTube stops a plain fetch after about a megabyte); the same relay feeds mpv and ffmpeg over loopback.
- **YouTube progress** is reported from the Pi for items queued from the phone that signed the Pi in only (the Pi records
  which phone handed the sign-in over), on pause, skip, an item ending and every 30 s; items from another phone are not.
  A second phone's sign-in hand-over is refused until the first phone signs the Pi out.
- **Volume is the device's own.** A play never changes a TV's or a speaker's volume; only the phone's volume slider does.
  The slider shows the level the device reports (a Chromecast says it on connect and when it changes), or "🔈 Volume not
  known" when it has not said. A volume saved by an older version is ignored.
- **A speaker's volume is the speaker's own** ([ADR 165](../adr/0165-a-speaker-is-kept-awake-and-has-one-volume-its-own.md)). The slider writes the Bluetooth transport's
  absolute `Volume` (`busctl`, 0 to 127) and shows what the speaker reports, including a change made on its own buttons;
  mpv stays at 100%. With no transport or no `Volume` property it falls back to mpv's volume and the line says "🔉 …
  Totum's, the speaker's own is not available". Reading never writes.
- **A speaker can be kept awake.** `TOTUM_SPEAKERS_KEEP_AWAKE=Soundmic` (or `Soundmic@07:00-22:30`; names or ids) keeps
  that speaker linked when a queue ends, reconnects it if it drops (2, 4, 8, 10 minutes apart) and sends two seconds of
  inaudible sound to its sink about once a minute while nothing plays (`TOTUM_SPEAKERS_KEEP_AWAKE_SIGNAL=silence|noise`).
  The remote panel says "🟢 Kept awake", "⏳ … reconnecting…" or "💤 … outside its hours". Unnamed speakers, the soundbar
  included, are untouched. Built and unit-tested 2026-11-13; **not yet seen on the real Soundmic**.
- **A sleep timer on a target** ([ADR 166](../adr/0166-the-sleep-timer-fades-by-how-loud-it-sounds-on-the-phone-and-on-a-target.md)).
  On a selected target's page, beside Repeat, the same sleep tile as the player's: after this item, the end of the group,
  or 5 to 60 minutes; tapping it while armed cancels. The timer runs on the Pi (it survives the phone going to sleep): it
  ramps the target's volume down over the fade length (Bedtime page, default 20 s, steady in how loud it sounds, never
  above the level it began at), pauses (or stops, when the last item ends), then puts the volume back after a second.
  A volume you set during the fade is followed down, never up. A target whose volume is not known is not faded and
  still stops. Not saved with the queue: a Pi restart forgets it.
- **TV sound switches** are greyed, with a note, while the TV is showing a picture.
- **A TV that is off is listed and wakes on a play.** `TOTUM_CAST_TARGETS` accepts `name=address@aa:bb:cc:dd:ee:ff`; such a
  TV shows 💤 on its chip and "💤 Off · tap to wake" in its header while it is off. Playing to it (or tapping that) sends
  Wake-on-LAN, shows "⏰ Waking LG TV…" on the progress strip, waits up to 25 s for it to answer, then plays; if it never
  does the strip says "❌ LG TV didn't wake: check 'Turn on via Wi-Fi' in its settings". Untested from the container (see
  ADR 152).
- **Live on a speaker** plays YouTube's HLS manifest for the video (via yt-dlp on the Pi), from the live edge; TVs get
  the DASH manifest as before ([ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)).
- **A slow start is watched, not guessed at** ([ADR 158](../adr/0158-targets-end-of-batch-fixes.md)). A queue request is
  accepted at once; a speaker that takes 15 s to link, a TV that is waking or a slow lookup show as "⏳ Starting on …" on
  the strip (the state says `starting`) and the phone watches until the Pi has played it or said why not. Only then, if it
  did not, does the phone play the items itself, taking them off the Pi by id. A target that cannot be reached is not asked
  for a stream first, and its problem is shown in the phone's own words ("📶 Couldn't reach … it isn't connected (off or out
  of range)", "🔌 Switch … on, then try again", "🔑 Sign in to the home server again", "⏳ YouTube is refusing the Pi for
  now; try again around 14:30").
- **A TV switched off mid-item stops the deck and keeps its place.** The deck shows it asleep with "📺 LG TV was switched
  off"; Play wakes it and resumes at the saved second (a live item at the edge). STOP then Play, and a Pi restart then Play,
  also resume. A playing item whose position has not moved for 20 s is a "stopped moving" problem.
- **The remote panel seeks.** A slider (sent on release) with 10 s back and 30 s forward; tapping the playing row does not
  restart it.
- **A reason stays visible.** When an item fails and the queue moves on, the target's problem stays on screen until the
  next item ends or you act ([ADR 155](../adr/0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md)).
- **STOP clears the TV.** If Totum opened the receiver app (the TV was idle or on its backdrop), STOP, or a queue that
  runs out, quits it, so the TV is not left on a blank "Default Media Receiver" screen; if another app was running first
  (YouTube, say) the TV is left alone.
- **A live stream has no length.** The strip shows "🔴 LIVE" instead of the bar and the clock (the TV's own "length" of
  a live stream is YouTube's 12-hour window, and `GET /targets` now sends none, with `live: true`).

- **▶️ Play on… is one remembered choice, offered everywhere** ([ADR 169](../adr/0169-play-on-is-a-choice-that-remembers-the-last-device.md)).
  The last used device is where every play, add, play next and shuffle all goes, from any tab, until another is picked; it
  is kept across restarts and no longer ends with the Queue tab. At home (the home server answered, and an unmetered
  connection) every device the Pi lists is offered, switched off ones with 💤; away from home only this phone is. A slim
  "▶️ Play on [device ▾]" row under every tab's header but the Queue's (which has the chips, the same choice: one picker per
  screen) shows and changes it, every item menu has "📡 Play on…" to play
  that item on a pick, and picking a switched-off device wakes it at once (a play to a sleeping one wakes it as before).
  The device's colour is on the item wherever it is playing (a "📺 LG TV" badge on rows, the queue, the mini player and the
  full player). Peek stays on the phone; the phone's own notification is not recoloured (a device has its own, [ADR 188](../adr/0188-the-selected-device-is-a-second-media-session-for-the-lock-screen-and-notification.md)).

## Files

| Piece | Where |
|---|---|
| Model, colours, send plan, reporting rule | `core/data/…/data/targets/` (`Targets.kt`, `TargetRules.kt`), `data/home/TargetsClient.kt` |
| PCM chain (cut, then boost) | `core/sound/…/PcmChain.kt` |
| Pi decks, routes, reporting | `cli/…/serve/targets/` (`TargetDeck`, `TargetDecks`, `TargetRoutes`, `DeckStore`, `YouTubeProgress`) |
| Pi sound pipelines | `cli/…/serve/sound/ProcessedSound.kt` |
| Speakers | `cli/…/serve/speakers/` (`Bluez.kt`, `MpvPlayer.kt` — now a `TargetPlayer`, `SpeakerSource`) |
| TVs | `cli/…/serve/cast/` (`ChromecastPlayer`, `PiStreams` (owns the `CastStreamServer`), `PiCastTvs`, `FfmpegAudioEngine`, `CastControl`, `CastWire`, `StreamRelay`), `server/totum-cli/chromecast_helper.py`; the brain in `core/cast` (`CastStreamServer`, `ProcessedAudio`, `AudioEngine`, `LiveRelay`, `DashManifest`, `CastRungs`, `ChunkedUpstream`, `LocalRanges`), `HomeDownloads.castOf` for a YouTube ladder |
| Phone | `app/…/targets/` (`TargetsRepository`, `TargetWords`, `SendToTarget`, `TargetColourStore`, `RequesterIdentity`), `ui/queue/` (`QueueWithTargets`, `TargetChips`, `RemoteQueue`, `SendToButton`), `queue/QueueDestination.kt` |

- **Repeat, files and problems (2026-11-07, [ADR 155](../adr/0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md)).** A
  target repeats off / this one / the queue (set by a queue request or a `REPEAT` control, saved, shown in `GET /targets`);
  an item can be a sound file on the Pi (`file:///sounds/<name>`, nothing outside that folder); a passed-over item or a
  failed player shows as the target's `problem`; `127.0.0.1:8772` on the Pi serves only `/targets*` and `/health`, for Home Assistant ([ADR 158](../adr/0158-targets-end-of-batch-fixes.md)).

- **End-of-batch fixes (2026-11-13, [ADR 158](../adr/0158-targets-end-of-batch-fixes.md)).** Accept-then-start, reach before
  resolve, resume from the saved place, a lost connection, a stall, edits and removals by item id, a structured problem
  (`TargetState.trouble`), the YouTube back-off wording, the seek slider, audio extensions only in `/sounds`, one kit per
  target, the cut-end race, the hand-back remembered across a restart (`handback-<id>.json`),
  `TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND` for speakers that must be switched on by hand.

## Browsers (ADR 170)

- **Open the Pi's address in any browser.** `http://<the Pi's LAN address>:8771` (the address is `TOTUM_CAST_BASE` without its
  scheme, and the phone shows it under **🖥️ Add a browser…** in the Play on… row and every item's "📡 Play on…" list, at home, with
  📋 Copy address). The page is the PC screen's receiver page, served by the Pi's own cast server: no key, nothing to
  install, nothing to type but the address, so a Fire Stick's Silk with its remote will do.
- **It is a device like any other** once it has said hello: `🖥️ Silk` (or Edge, Chrome, …) in the picker with a colour of its own, a
  queue of its own, row badges, remote-queue controls (play, pause, seek, skip silences, boost, volume, sleep timer). It drops out of the
  list a minute after the page is closed (its deck says "🖥️ Silk was closed" at once when the page says goodbye, or after 30 s of silence),
  and comes back with its queue when the same browser opens the page again. Two tabs of one browser are one target: the newest tab
  plays and the older says it was replaced. The browser's own volume is what a sleep timer fades from.
- **The Pi does the work, so the phone can be off.** The picture is relayed and the sound processed on the Pi
  ([ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md)); the page plays the manifest from its own
  origin. The page's seek bar seeks the Pi's queue.
- **A phone from before 2026-11-14 (v0.1.911 and earlier) is answered as it expects**: the Pi waits for the start rather than
  answering "starting" ([ADR 158](../adr/0158-targets-end-of-batch-fixes.md)).
  When the Pi stops answering the phone says "🏠 Home devices unreachable" in the picker row and the item menu instead of quietly
  falling back to itself ([ADR 169](../adr/0169-play-on-is-a-choice-that-remembers-the-last-device.md)).
- **Same network as the Pi, for now.** A friend's network cannot reach the Pi's LAN address; what the remote case would need (a public
  name in nginx, pairing so that only a browser the phone approved is a target, an uplink decision) is in the ADR. Not built.
- **A device switched on by hand says so**: "🔌 Off · switch it on by hand" in the picker, no wake sent when it is picked,
  `TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND` on the Pi; and one colour per device on every surface (`TargetColours.of`).

## Power: how a device comes on and goes off (ADR 177)

- **One seam.** A target's way to come on lives in one place on the Pi (`DevicePower`, `cli/.../serve/power/`): wake-on-LAN,
  an Android stick woken and an app started over adb, a display switched on and put on an input, or by hand. The picker's
  "💤 Off · picking it switches it on" and the deck's wake-then-play both ask it. A device that starts an app says "💤 Not
  running · picking it starts it"; what a wake is doing reads in the header ("🔌 Starting Kodi on the Fire Stick…").
- **LG webOS.** `TOTUM_WEBOS_TARGETS=LG TV=192.168.0.219`, paired once with `totum webos-pair "LG TV"` (the TV shows a
  PIN). A paired LG is on only while its panel says `Active` (port 3001 stays open in standby), can be put on an input and
  can be switched off. **⏻ Switch off** is on the remote panel and next to the selected chip when the Pi says it can.
- **Sleep timer.** A checkbox under the timer, off by default, switches the device off after the fade, the pause and the
  volume being put back.
- **Kodi on the Fire Stick.** `TOTUM_ADB_TARGETS=Kodi=192.168.0.211:5555|the Fire Stick`, `TOTUM_START_APPS=Kodi=org.xbmc.kodi/.Splash`
  and optionally `TOTUM_SHOWN_ON=Kodi=LG TV@HDMI_1`. A Kodi that does not answer has its stick woken (only if asleep) and Kodi
  started, again every 20 s for up to a minute; otherwise "❌ Kodi didn't start".
- **Seen on the real devices, 28 Nov 2026** ([ADR 177](../adr/0177-a-device-says-how-it-comes-on-and-goes-off-lg-over-webos-kodi-started-over-adb.md)):
  the webOS switch-off worked; wake-on-LAN did not wake the LG from deep standby, but the Fire Stick's sleep-then-wake did (it
  tells the TV over HDMI-CEC). So a paired TV is **on only while webOS says `Active`** (the cast port never makes it on; when webOS cannot be
  asked and the port answers, the state is held as it was, "unknown, maybe on", and asleep only once the port is silent too; every
  change logs what webOS and the port said), a TV with a stick shown on it (`TOTUM_SHOWN_ON`) is woken
  by wake-on-LAN first and then, after 5 s of silence, by cycling the stick's sleep and wake (45 s allowed), a Kodi on an awake
  stick whose TV is off gets the same cycle (never while webOS says Active or the cast port answers: "maybe on" sends nothing and
  leaves the wake to wake-on-LAN), and a volume with nowhere to go is refused in words
  (`⚠️ Couldn't set LG TV's volume to 2: …`) instead of logged as set. Kodi's refused sign-in reads
  `🔑 Kodi refused the sign-in: check its web server password`, not "isn't connected". Built the same day against the fakes, **not
  yet run on the real TV**; see [webos-and-kodi-power](../todos/webos-and-kodi-power.md).

## Not built (see the todo)

A name typed into the browser page (a stored name is read, none is written); the browser page away from home; Home Assistant's brown noise and jazz through Totum (the Totum side is built; the dot-files adapter is in `tools/handoff/hermes-audio/`, not deployed); the mini-player pill; "Play on…" as a long press on every play button; skip silences on a picture (the phone does
not either); playback speed on a target; the phone's silence mode and speech gate on a target; TV volume in the strip.
Not seen on a real TV or speaker yet: the manifest, the relayed picture, the Pi's segments, live on a TV and on mpv.

## Tests

ADR 158 (2026-11-13): `TargetDeckStartTest`, `TargetRoutesSlowStartTest`, `TargetDecksTest`, `SoundFilesTest`,
`TargetStreamsTest`, `BluezTest`, `CastBrainTest`, `CastControlTest`, `CastWireTest`, `WakeThenPlayTest` (cli),
`TargetsRepositoryTest` (app), `chromecast_helper_test.py`, `test_audio_ctl.py`; `TargetWordsTest` (instrumented, not run).

ADR 170 (amended 2026-11-14: `ScreenLinkTest`, `ChromecastPlayerTest`, and the additions to `BrowserControlTest`, `TargetDeckStartTest`,
`TargetDecksTest`, `TargetRoutesSlowStartTest`, `PlayOnTest`, `TargetsRepositoryTest`, instrumented `PlayOnPickerTest`):
`ScreenOnThePiTest` (core/cast: the Pi serves the page at the bare address with its host, no key on the Pi's page and the key
still needed on the phone's, rename and arrival tell the list, a cap on listed screens), `BrowserControlTest` (load, reports, stale
loads, one end, seek and volume, reachability, lost), `BrowserSourceTest`, `BrowserServingTest` (the real page over a real socket, a
browser listed at once and renamed in place), `BluezTest` and `TargetDeckSpeakerTest` (by hand), `PlayOnTest`,
`TargetStateWireTest`, `TargetRulesTest` (core/data), `TargetsRepositoryTest`, `DeviceMarksTest`, `DeviceColourRuleTest` (app),
instrumented `PlayOnPickerTest` and `DeviceColourSurfacesTest`.

ADR 177: `SteppedPowerWolTest`, `KodiStartTest`, `KodiStartThenPlayTest`, `DevicePowersTest`, `WebOsTvTest`, `PinnedTrustTest`, `WebOsPairingTest`, `TargetDeckPowerOffTest`, `CommandParsingTest`, `PlayOnTest`, `TargetsRepositoryTest`; stand-ins `FakeSsapServer` and `FakeAdb`. Added 2026-11-28 after the real-device run: `SteppedPowerDeferredStepTest`, `ShownOnStepTest`, `CecNudgeTest`, `KodiUnreachableWordsTest`, `TargetDeckVolumeRefusalTest`, and the flapping-check, evidence and CEC tests in `DevicePowersTest`.

Unit (wake, hand back, live, ADR 152 amendments of 2026-11-07): `WakeOnLanTest`, `SteppedPowerWolTest` (was `TvWakeTest`), `WakeThenPlayTest`, `YtDlpLiveHlsTest`, `LiveForSpeakersTest`, `CastWireTest`, `CastControlTest`, `ChromecastPlayerTest`, `TargetDeckTest`, `TargetDeckStartTest`, `TargetDeckVolumeSafetyTest`, `SpeakerVolumeTest`, `MpvPlayerTest` (cli), `TargetRulesTest` (core), `TargetsRepositoryTest`, `TargetChipLabelTest` (app), `server/totum-cli/chromecast_helper_test.py`.

Unit: `PcmChainTest`, `TargetRulesTest`, `TargetEntriesTest` (core), `TargetDeckTest`, `YouTubeProgressTest`,
`PcmPumpTest`, `CastWireTest`, `CastControlTest`, `YouTubeOwnershipTest` (cli); `TargetsRepositoryTest`, `PlayLocallyTest`,
`SignInHandoverTest`, `RequesterIdentityTest` (app); and from the 2026-11-07 testing stretch `TargetRoutesTest`,
`TargetDecksTest`, `DeckStoreTest`, `MpvPlayerTest`, `ChromecastPlayerTest`,
`ProcessedSoundEndToEndTest` (cli), `RedirectEntryPointsTest`, `SendToTargetTest` (app); and from the two fixes after the
first run on the LG `StreamRelayTest` (cli), `ChunkedUpstreamTest`, `LocalRangesTest` (core/cast) for the relay, and the
volume tests in `TargetDeckTest`, `ChromecastPlayerTest`, `MpvPlayerTest`, `CastControlTest`, `CastWireTest`; and from ADR 155 `TargetRepeatTest` (core), `SoundFilesTest` and the repeat, problem and requester cases of `TargetDeckTest`, `TargetRoutesTest`, `TargetStreamsTest`, `MpvPlayerTest` (cli). Owed: see the todo.

From ADR 156: `CastBrainTest` (a normal video loads exactly the manifest the phone builds, picture relayed by range, sound-only, live on a TV and on a speaker, cut position and end, the wire), `FfmpegAudioEngineTest` (segments, seeks, cuts, stale stop, length, silent frame, ADTS), `PiCastTvsTest`, and the `streamFor` cases of `TargetStreamsTest` (cli); `EmbeddedSubtitlesTest` moved to `:core:cast`. The phone's `CastStreams` is covered by the same core tests and by its instrumented tests, not run in this change.

## A running Jellyfin app is a target (ADR 187, built 2026-11-20)

`TOTUM_JELLYFIN_CLIENTS=<label>=<device name contains or device id>[;...]` makes each named Jellyfin client (the Fire Stick's
app, the LG's, a web client) a TV-kind target, listed whether or not the app is open: `ON` while Jellyfin lists a
controllable session for it, `ASLEEP` with "🔌 Jellyfin is closed · open it by hand" otherwise. The Pi hands the app the item id
(`PlayNow` with the start position) through Jellyfin's Sessions API and watches the session through the shared `RendererWatch`;
pause, resume, seek, volume and stop are the app's own commands. It plays only Jellyfin items: `TargetState.plays` says
`JELLYFIN_ONLY`, the picker says "🎞️ Jellyfin films and episodes only", the Pi refuses anything else with a reason, and a
batch with anything else in it plays on the phone. Not run against a real client yet; the backlog item
[jellyfin-remote-control](../todos/jellyfin-remote-control.md) has the Pi runbook.

## Home Assistant's sounds (ADRs 155, 158)

The adapter that answers HA's `:8099` URLs by asking these targets has been live since 2026-11-07; its contract with `GET
/targets` and `POST /targets/queue` is two golden fixtures checked from both sides
([backlog item](../todos/home-assistant-sounds-through-totum.md)).
