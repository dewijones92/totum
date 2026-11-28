---
title: A device says how it comes on and goes off in one place; the LG is driven over webOS and a Kodi Android has killed is started over adb
kind: adr
status: accepted
updated: 2026-11-28
---

# 177. A device says how it comes on and goes off in one place; the LG is driven over webOS and a Kodi Android has killed is started over adb

- Status: Accepted
- Date: 2026-11-20
- Builds on: [ADR 158](0158-targets-end-of-batch-fixes.md) (wake-on-LAN, `TvWake`, which this replaces),
  [ADR 170](0170-a-browser-is-a-target-the-pi-plays-to-found-by-opening-its-address.md) ("switch it on by hand"),
  [ADR 169](0169-play-on-is-a-choice-that-remembers-the-last-device.md) (the picker's "picking it switches it on"),
  [ADR 166](0166-the-sleep-timer-fades-by-how-loud-it-sounds-on-the-phone-and-on-a-target.md) (the sleep timer),
  [ADR 175](0175-dlna-airplay-and-kodi-carry-the-picture-over-the-one-stream-servers-hls.md) (`KodiControl`)
- Backlog item: [cast-to-airplay-and-dlna](../todos/cast-to-airplay-and-dlna.md)

## Context

Dewi, 2026-11-14: the LG TV (192.168.0.219) should be switchable off from Totum and by a sleep timer, and Kodi on the Fire
Stick (192.168.0.211) should start itself, because Android kills Kodi in the background and a Kodi target that does not
answer was simply "off or unreachable". Before this, only a TV with a MAC could be woken (`TvWake`, wake-on-LAN, in
`CastDeviceSource`), nothing could be switched off, and a play to a Kodi that was not running failed. Three more kinds of
power were wanted, and each would have been a copy of `TvWake` and of the phone-side words for it.

## Decision

- **One seam, `DevicePower` (`cli/.../serve/power/`)**, behind both the picker's "picking it switches it on" and the deck's
  wake-then-play. A `SteppedPower` is built from a list of **wake steps** (`WakeStep`: wake-on-LAN, wake an Android stick,
  start an app, switch a display on), one **power-off way** (`PowerOff`), and an `answers()` check; it runs the steps, repeats
  the ones that repeat, polls `answers()` until a timeout, then runs the **ready steps** (put the TV on this device's input).
  `TvWake` is now `SteppedPower` with one `WolStep`; `WakeOnLan` moved to the same package. A target with no way to wake it
  is left alone (a play to it is not held); one with no way to switch off says "Totum has no way to switch it off".
  `CastLink` and `ChromecastPlayer` take a `DevicePower`, and `TargetLink` exposes `startsApp`, `canSwitchOff` and `powerNote`
  beside `switchedOnByHand`, so the phone has one place to ask how a device comes on.
- **Each target says how it comes on**, and the words follow: wake-on-LAN or webOS ("💤 Off · picking it switches it on"),
  an app to start ("💤 Not running · picking it starts it"), by hand ("🔌 Off · switch it on by hand", unchanged). The
  state carries `startsApp`, `canSwitchOff` and `powerNote` (what a wake is doing right now), none of them saved with the queue.
- **LG webOS over SSAP** (`WebOsTv`, `WebOsSession`, `WebOsWire`, `OkWebOsDial`): wss on 3001 (ws on 3000 only for a TV with
  no pinned certificate). 3001 stays open in standby, so an open port never means the TV is on: whether the panel is on is
  `getPowerState == "Active"`, and `"Active Standby"` or `"Suspend"` are off. The port probe is only the fallback when the
  TV cannot be asked (not paired, not reachable). Power off is `system/turnOff`; a TV that closes the connection as it goes
  to standby has been switched off, not failed. Input select is `tv/switchInput`.
- **Pairing is by PIN, driven by the CLI**: `totum webos-pair "LG TV" [--pin=NNNN]`. The TV shows a PIN; the PIN is typed at
  the terminal or handed to the waiting pairing by a second `--pin=` run (a marker and a 600 file in the same folder). The
  client key and the TV's certificate fingerprint are stored in `$TOTUM_DATA/webos/webos-<id>.json`, mode 600 (written to a 600
  file and renamed, never created wider), and **never printed or logged** (`WebOsKey.toString` redacts it; the tests read every
  log line and every printed line for it). The certificate is pinned at pairing; a changed certificate is refused (the TV then reads as
  unreachable, with the reason in the log), and a key the TV refuses says "run totum webos-pair again" and is not retried on
  every poll.
- **⏻ Switch off** is a `TargetAction.SWITCH_OFF`: on the remote panel and next to the selected target's chip, offered only
  when the Pi says `canSwitchOff` and the device is not already off. It stops what plays, hands the TV back, then switches it off.
- **A sleep timer can switch the TV off afterwards, off by default** (`TargetSleepAsk.switchOff`, a checkbox under the timer,
  shown only for a device that can be switched off): the existing fade and pause run first, the volume is put back, and only
  then is the device switched off. The state says "⏻ … will be switched off when the timer ends" while it is armed. A target
  that cannot be switched off is still stopped, and the Pi logs why it did not switch it off.
- **Kodi start over adb** (`Adb`, `AdbWakeStick`, `AdbStartApp`): when a Kodi target does not answer, the Pi connects to the
  stick, reads `mWakefulness`, sends `KEYCODE_WAKEUP` only if it is asleep or dozing, then `am start -n org.xbmc.kodi/.Splash`,
  and repeats the start every 20 s for up to 60 s. While it does, the phone reads "🔌 Starting Kodi on the Fire Stick…" (the
  device's own name and its place); if Kodi never answers, "❌ Kodi didn't start". A Kodi shown on a TV
  (`TOTUM_SHOWN_ON=Kodi=LG TV@HDMI_1`) also has the TV put on, and on that input, once Kodi answers; switching that Kodi off
  switches the TV off. The stick itself is never switched off.
- **Configuration is four names in `fetch.env`, no code**: `TOTUM_WEBOS_TARGETS` (`LG TV=192.168.0.219`),
  `TOTUM_ADB_TARGETS` (`Kodi=192.168.0.211:5555|the Fire Stick`), `TOTUM_START_APPS` (`Kodi=org.xbmc.kodi/.Splash`) and
  `TOTUM_SHOWN_ON`; `TOTUM_ADB` names the adb binary if it is not on the path. A name that is not a configured device is
  warned about, and two devices shown on each other are cut, with a line saying so.
- **adb in the `totum-fetch` image**: the Dockerfile's `fetch` stage installs `adb` (the Ubuntu `adb` package; the base image
  is Ubuntu 24.04), and the compose file mounts the Pi's `/home/pi/.android/adbkey` read-only at
  `/home/totum/.android/adbkey`, the same key the Fire Stick already authorises (dot-files' `firestick-remote` container
  mounts it the same way), so there is no new "Allow USB debugging?" prompt on the TV and no key of Totum's own. adb derives
  the public half from the private key.
- **Logging**: every wake step, repeat, power change and switch-off logs what it did and why (`cast-target`, `webos`, `adb`,
  `webos-pair`), including "not answering and Totum has no way to wake it", "still answering, but switched off just now" and the
  reply `am start` gave. A key, a PIN or a certificate never appears; a PIN is logged as its length.

## Consequences

- A new way to power a device is one `WakeStep` or one `PowerOff` and a line of configuration, not a new class wired through
  the source, the player, the link and the phone's words.
- `TvWake` and `TvWakeTest` are gone; their tests moved to `SteppedPowerWolTest`, with the same assertions (the packet, the
  resend, the 25 s timeout and its words, the direct unicast packet).
- An LG TV that is paired answers "on" from its panel, not from a port, so a TV in standby is listed 💤 and woken as before,
  and a TV switched off from Totum is held asleep for ten seconds even if its port still answers.
- Kodi is started only when a play (or a tap on the 💤 row) asks for it; nothing polls the stick to keep Kodi running.
- The key is stored by the CLI that paired it and read by `totum serve` every time it is needed, so re-pairing needs no restart.

## Tests, and what turned them red

The stand-ins are a **fake SSAP websocket server** (`FakeSsapServer`, on `MockWebServer`: hello, signed and unsigned register,
PIN pairing, `getPowerState`, `turnOff` answered or closed on, `switchInput`, `getExternalInputList`) and a **fake adb shell**
(`FakeAdb`, a `Shell` that answers `connect`, `dumpsys power`, `pidof` and `am start`, and records every command). The tests
that were written after the code were each turned red by a one-line mutant of the code, then the code was put back (run
2026-11-20): the panel counted on for any state (`WebOsTvTest`, `DevicePowersTest`); a close after `turnOff` counted as a failure,
and a close by the TV not answered so it is waited out for 8 s (a real defect the fake found: the client had no `onClosing`);
no settle after a switch-off; the wake key sent to a stick that is awake; the key file created world-readable; the sleep timer
switching off by default; a switch-off that does not stop what plays first; Kodi's start never repeated; a one-digit PIN
accepted; a refused key retried on every poll; an `am start` error counted as started; the wake failure's words not passed
to the phone; the certificate not stored with the key (the first test missed this one: `ws://` has no certificate, so a
dial wrapper that presents one was added); and a start of an app shown on the strip as a plain wake.

## Not verified against the real devices

(Superseded in part: the real LG and Fire Stick were run on 2026-11-28, see "Seen on the real devices" below.)

Nothing here was paired with, powered, switched or started on the real LG or the real Fire Stick: every test runs against a
fake SSAP websocket server (`FakeSsapServer`, on `MockWebServer`) and a fake adb shell (`FakeAdb`). What is therefore open until
Dewi runs it once: that this LG accepts the signed registration manifest (the unsigned one is tried when it refuses), that its
`getPowerState` says `Active Standby` rather than another word while off, that `system/turnOff` is permitted with a PIN-paired
key, and that the Pi's adb key is the one the stick has authorised.

## Amended 2026-11-27: a switch off that worked was reported as "no way to switch it off"

The first run on the real LG (paired over webOS the same day) switched the TV off, which the webcam confirmed, and the deck
then logged `switch off failed: Totum has no way to switch it off`, so the phone saw a failure for something that worked. The
cause was one line: `CastLink.switchOff` was `wake?.switchOff() ?: "Totum has no way to switch it off"`, and a successful
`switchOff` returns `null`, so the elvis turned success into the "no way" text. It is now an explicit check for a link with
no power way, and the power way's own answer (`null` for done, the reason for a failure) passes through untouched.
`CastLinkSwitchOffTest` (4) covers the link and the state the phone reads: no problem, no trouble, the TV asleep; two of its
four failed against the old line (`expected null, but was:<Totum has no way to switch it off>`). The treat button
([ADR 196](0196-the-treat-button-puts-a-film-on-the-tv-for-a-while-and-the-pi-ends-it.md)) depends on this, because its end
action is `switchOff`.

The same run logged `webOS control unavailable, it could not be reached: the TV closed the connection (1008)` after the
switch off. That line was not investigated here; once the TV is off the power check can be expected to fail to reach it,
which is not shown to be the cause.

## Amended 2026-11-27: a close right after our own switch off is the TV powering down, not "unavailable"

The `(1008)` line above was read again on 2026-11-27: after a successful switch off the Pi's next webOS call (the power-state
poll behind the TV's on/off dot) was met by the TV closing the socket with code 1008, and `WebOsTv.ask` reported it as `webOS
control unavailable, it could not be reached: the TV closed the connection (1008)` at WARN, and recorded it as the last
unavailable reason. A TV closing the line as it powers off is expected.

- The transport now says *who* closed the line: a close the TV began (`onClosing`/`onClosed`) is a `WebOsClosedByTv(code)`, still
  an `IOException`, and the one a send onto an already closed socket meets as well; any other failure stays a plain
  `IOException`.
- `WebOsTv` remembers when Totum last switched the TV off (`switchedOffAtMs`, only on success). A `WebOsClosedByTv` **within two
  minutes of that** is logged once at INFO as `LG TV: the TV closed the connection as it switched off (code 1008, Nms after Totum
  switched it off); webOS control is not marked unavailable`, returns the same words as the call's reason, and touches neither
  `lastUnavailable` nor the stored-key state. The power-state poll still reads `null` (unknown) for that call, so the dot falls
  back to the port probe exactly as before.
- A close at any other time (the TV was not just switched off by us) keeps the old warning with the old words, so a TV that really
  drops the line while on is still reported. Switched off by the remote, not by Totum, is therefore still a warning.
- Tests (`WebOsTvTest` +3, the fake SSAP server gained `closeNewConnectionsWith`): a close with 1008 just after our switch off logs
  the new line and no "unavailable" warning; a close with no switch off of ours still warns with the old text; a close ten minutes
  after the switch off is not taken as part of it. Seen red first: `LG TV: webOS control unavailable, it could not be reached: the
  TV closed the connection (1008)` was in the warnings.
- Not confirmed on the real LG: that the 1008 is what this TV sends in every case, and how soon after `turnOff` it comes (the
  two-minute window is a guess generous enough to cover a poll interval).

## Seen on the real devices, 28 Nov 2026

Between 02:21 and 02:29, on the Pi's `totum serve` v0.1.973, against the LG (webOS at 192.168.0.219, paired, a Chromecast
built in as target `lg-tv`) and the Fire Stick running Kodi (target `kodi`, adb 192.168.0.211:5555, shown on the LG's HDMI_1),
with the TV in deep standby. Read from `docker logs totum-fetch`, the webcam and `adb` by hand.

**What worked.**

- The webOS switch-off: `switched off (webOS system/turnOff)`, then the TV's 1008 close, handled as ADR 177's amendment of
  27 Nov says. The switch-off path is unchanged by what follows and its tests stand.
- Driving the stick by hand over adb from the Pi: `input keyevent KEYCODE_SLEEP`, six seconds, `KEYCODE_WAKEUP`. Within about
  seven seconds the TV's screen lit and webOS on 3001 accepted connections. The stick announces itself to the TV over
  HDMI-CEC ("Image View On") when it wakes, and that wakes the TV from deep standby.

**What did not, and what each fix is.** Each is a fix built the same day, proved only against the fakes (no real device was
touched by the change); the real-device run is owed.

- **The power check flapped.** With the TV dark and untouched the target flipped `ASLEEP -> ON` and `ON -> ASLEEP` on every
  cycle while webOS said `no answer within 5000ms`. The cause: `DevicePowers` answered `webos.panelOn() ?: portsAnswer()`, so
  whenever webOS could not be asked the Chromecast port (8009) decided. That the TV's cast port answers intermittently in
  networked standby is the presumed reason it flipped (the code path is certain; the port's behaviour was not measured, and
  the new evidence in the log will show it). Now `TvPowerProbe` decides: a **paired** TV is on only while webOS says `Active`, and webOS being unreachable
  is OFF, whatever the cast port says; the port decides only for a TV with no webOS or one that was never paired. This is
  stricter than ADR 177's "the port probe is the fallback when the TV cannot be asked (not paired, not reachable)": **not
  reachable no longer falls back**, and the cost is that a TV that is on but whose webOS is down (or whose stored key it
  refuses) reads as asleep. Every power change now logs the evidence once, e.g. `LG TV: ON -> ASLEEP (power check) [webOS
  power state Active Standby; cast port 192.168.0.219:8009 answers (not used: webOS is paired, so it decides)]`; the wake
  lines carry it as well.
- **Wake-on-LAN did not wake the TV from deep standby.** Five rounds over 25 s went unanswered (`did not answer within
  25000ms of the wake; ❌ LG TV didn't wake…`). Two changes, both through the sticks that `TOTUM_SHOWN_ON` already names (no
  new setting):
  - the **CEC nudge** (`CecNudge`, `AdbSteps.kt`): put the stick to sleep, wait six seconds, wake it, which is exactly what
    was done by hand. One nudge per stick, a lock around it, and none within 30 s of the last (a TV that is still coming on
    must not be sent a sleep, which is a CEC standby), logged as skipped when it is.
  - **A fallback wake step for the TV itself** (`CecWakeStep`): for a TV with a stick shown on it, after wake-on-LAN has been
    sent and 5 s have passed in silence (one resend), the stick is nudged. `WakeStep` gained `startsAfterMs`, and
    `SteppedPower` holds such a step back until the device has been silent that long and never runs it if the device
    answers first. The order is the order of the log: `waking it (wake-on-LAN to …, wake LG TV over HDMI-CEC through Kodi
    (adb sleep -> wake) after 5000ms of silence)`, then `silent 5000ms after the wake, now trying …`. A TV with no MAC has the
    CEC step straight away. The wake timeout for such a TV is 45 s, not 25 s (the CEC cycle alone is 6 s and the TV took
    about 7 s after it).
  - **The nudge never runs while there is any sign the TV is on** (added the same day, after review). A stick's sleep sends the
    TV a CEC standby, so nudging a TV that is on but whose webOS is down would switch it off under someone watching. The
    nudge runs only when webOS does not say `Active` (or cannot be asked) **and** the cast port (8009) is silent. If the
    port answers while webOS is down or says standby, that is "unknown, maybe on": nothing is sent, the wake is left to
    wake-on-LAN alone, and the log says why (`CEC nudge not sent (LG TV did not answer the wake-on-LAN): cast port
    192.168.0.219:8009 answers while webOS unavailable (…): unknown, maybe on; a sleep would send the TV a CEC standby, so
    the wake is left to wake-on-LAN alone`). The check is made at the moment of the nudge (`DevicePower.signOfLife`,
    `TvPowerProbe`). The cost: the cast port answered intermittently in networked standby (the presumed cause of the flap),
    so on such a night a nudge can be withheld at that moment, and the wake then rests on wake-on-LAN, which did not work
    from deep standby. Withheld nudges are logged, so the next real run shows how often.
  - and the Kodi wake: an **already-awake stick** was sent no key (`its Android device is already awake (mWakefulness=Awake),
    no wake key sent`), so the TV never heard it. `AdbWakeStick` now, when its stick is awake and the display it is shown on
    is not answering (and is not already being woken), nudges it, and says so.
- **A volume set on a target with no link claimed success.** `control VOLUME volume=2` on `lg-tv` with `linked=false` was
  logged `volume set to 2 … followed by a fade=false` and nothing was applied (`volume` stayed `null`): `PyCastControl.volume`
  sends to a helper that is not running and says nothing. Now `TargetPlayer.volumeRefusal()` and `CastControl.volumeRefusal()`
  (default none) say when a volume has nowhere to go; the deck logs `volume 2 NOT set: <reason>` instead of "set", and puts
  `⚠️ Couldn't set LG TV's volume to 2: no connection to LG TV is open, so there is nowhere to send it` in the state's
  `problem` (a `WONT_WAKE` trouble carrying its own words, the contract `didn't switch off` already uses, so no new
  `TroubleKind` that an old phone could not read). The next volume that does go through, or any play, clears it. A speaker
  with a hardware volume control is never refused.
- **Kodi's HTTP 401 was reported as "not connected".** `[cast-target] Kodi: Kodi refused the sign-in (HTTP 401)` was in the
  log while the state read `📶 Couldn't reach Kodi: it isn't connected (off or out of range)`. `CastControl.whyUnreachable`
  (default none) carries a known reason from `reachable()`; `KodiControl` sets it for a refused sign-in
  (`🔑 Kodi refused the sign-in: check its web server password`) and for no credentials file
  (`🔑 Totum has no sign-in for Kodi: check TOTUM_KODI_CREDENTIALS`), `CastLink.connect` puts it in the link's trouble, and a
  Kodi that does not answer at all still says "isn't connected". The 401 itself is a credentials problem on the Pi, not
  fixed here.
- **A false claim in the Kodi readiness line.** `Kodi: LG TV on, switched to input HDMI_1 did not succeed during getting it
  ready` was logged with the LG dark and webOS unreachable: the step's name was a claim. `ShownOnStep` is now named for the
  intent (`bring up LG TV and put it on input HDMI_1`), asks for the input only when the TV really is on (it used to dial a
  webOS that could not answer), and logs what happened: `LG TV did not come on (woke=false, power=ASLEEP); input HDMI_1 was
  not asked for` or `LG TV is on; switched to input HDMI_1`. A display with no way to be woken that is asleep is no longer
  counted as on (`ensureAwake` answers true for "nothing to try").

(Superseded in part, see "Amended 2026-11-28, after v0.1.978": webOS unreachable no longer reads as asleep while the cast
port answers.)

**Tests, and what turned them red** (run 2026-11-28 against the old code with the new API shaped but behaving as before):
`DevicePowersTest` (+12; the three of the sign-of-life guard were run red separately, `expected:<[]> but was:<[sleep, wake]>`, for a
TV with webOS down and the cast port answering through the TV's own CEC step and through an awake Kodi stick, and for a TV
webOS says is on standby with the port answering): `expected:<ASLEEP> but was:<ON>` for the flapping check (a port that answers while webOS cannot be
reached), the evidence missing from the power-change line, `expected:<[sleep, wake]> but was:<[]>` for the awake stick with
its TV off, for the TV woken over CEC after wake-on-LAN, for the TV with no MAC, and for a TV that stays dark getting one
cycle; `SteppedPowerDeferredStepTest` (3): `expected:<[cec@5000]> but was:<[cec@0]>`; `ShownOnStepTest` (4): `LG TV on,
switched to input HDMI_1` in the log and `[ssap://system/getSystemInfo, ssap://tv/switchInput]` asked of a TV that was off;
`CecNudgeTest` (6); `KodiUnreachableWordsTest` (4): `expected:<🔑 Kodi refused the sign-in: …> but was:<📶 Couldn't reach
Kodi: it isn't connected …>`; `TargetDeckVolumeRefusalTest` (4): `expected:<[]> but was:<[2]>`. The tests that pin what did not
change (an unpaired TV judged by its port, an awake stick whose TV is on left alone, a wake-on-LAN that works leaves the stick
alone) passed before and after, on purpose. One test harness trap: the first flap test passed against the old code because its
listening `ServerSocket` never accepted a connection, and the port most likely stopped answering once the backlog filled (not
measured; the old code was seen answering `true` on a direct probe, then `false` a few probes later); the test listener now
accepts and closes, and the test went red as it should.

**Still not proven on the real devices:** that the cycle wakes the LG when it is sent by Totum rather than by hand, that
five seconds of wake-on-LAN is the right moment to give up on it (the packets reached nothing that night, so the real
timing of a wake that does work is unknown), and how often the sign-of-life guard withholds a nudge on the real TV.

## Amended 2026-11-28, after v0.1.978: one failed webOS connect with the TV on is not "asleep"

v0.1.978 on the Pi woke the LG by wake-on-LAN in 4.3 s, and the webOS switch-off and the treat timer worked. One flap remained:
with the TV on and casting, right after the receiver app quit, one webOS connect failed (`could not connect (wss://192.168.0.219:3001:
no answer within 5000ms)`) with the cast port answering, the check logged `ON -> ASLEEP`, and five seconds later `ASLEEP -> ON`.
The rule above, "webOS unreachable is off", was too strict, and the CEC guard (never nudge when the port answers) had already
said as much.

- **One rule, in one place** (`TvPowerProbe`): a paired TV's webOS is `Active` (on), answers anything else, `Suspend` or standby
  (asleep), or **cannot be asked**. Cannot be asked with the cast port silent is asleep; cannot be asked with the cast port
  answering is **unknown, maybe on**. The power check holds the state it had when the answer is unknown (it logs once, `LG TV:
  power check unsure [webOS unavailable (…); cast port … answers (webOS is paired but could not be asked: unknown, maybe
  on)]; keeping ON until it is answered or its cast port goes silent`), and the CEC nudge refuses on the same "unknown" (and, more
  strictly, on any answering port, since a sleep key sent to a TV that is on switches it off). Only "webOS answered a non-Active
  state" or "webOS unreachable and the port silent" move the state to ASLEEP.
- **Consequence for a dark TV at start-up:** the state starts as ON, so a dark TV whose cast port happens to answer while webOS is
  down stays shown as on until the port goes silent or webOS answers; once it is ASLEEP, an answering port with webOS down no
  longer flips it back (the first flap).
- **Not changed, and open:** `ensureAwake` (a play to the TV) still treats "webOS could not be asked" as not answering, so a play
  that lands in such a blip on a TV that is on sends wake-on-LAN and waits out the 45 s before failing. Treating "unknown" as awake
  there would also stop the wake-on-LAN being sent for a TV that is only maybe on; left for Dewi to decide.
- Tests: `DevicePowersTest` (+2). The reported sequence (webOS `Active`, then one refused connection, then `Active` again, with the
  port up) was red first, `the blip read as ASLEEP expected:<ON> but was:<ASLEEP>`, and now holds ON with one "unsure" line and
  no `ON -> ASLEEP`/`ASLEEP -> ON`; webOS unreachable with the port silent, and webOS answering `Suspend` with the port up, are
  still asleep. The earlier "webOS down, port answering is asleep" test was rewritten to the case that remains true (a TV that is
  already asleep stays asleep when the port starts answering).
