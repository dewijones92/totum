---
title: A device says how it comes on and goes off in one place; the LG is driven over webOS and a Kodi Android has killed is started over adb
kind: adr
status: accepted
updated: 2026-11-20
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

Nothing here was paired with, powered, switched or started on the real LG or the real Fire Stick: every test runs against a
fake SSAP websocket server (`FakeSsapServer`, on `MockWebServer`) and a fake adb shell (`FakeAdb`). What is therefore open until
Dewi runs it once: that this LG accepts the signed registration manifest (the unsigned one is tried when it refuses), that its
`getPowerState` says `Active Standby` rather than another word while off, that `system/turnOff` is permitted with a PIN-paired
key, and that the Pi's adb key is the one the stick has authorised.
