---
title: 🔴 An infrared blaster so Totum can act as a remote, and more Bluetooth around the house
kind: todo
status: idea
priority: high
updated: 2026-11-27
---

# 🔴 An infrared blaster, and more Bluetooth around the house

Dewi, 2026-11-27: *"buy you an infrared blaster so you can act as a remote … more Bluetooth stuff around the house … top of
the next backlog, refine later."* Not refined: no scope, no decision, nothing built.

## Evidence from 2026-11-27

- **The LG in deep standby answered neither wake-on-LAN nor the Fire Stick's CEC.** Powering it on from Totum is not certain
  today ([webos-and-kodi-power](webos-and-kodi-power.md), [ADR 177](../adr/0177-a-device-says-how-it-comes-on-and-goes-off-lg-over-webos-kodi-started-over-adb.md)).
  Switching it off over webOS works (the webcam saw it go off), so only the power-on is unreliable.
- **An infrared blaster on the Pi, or one integrated with Home Assistant, would make power-on certain**, because the LG's own
  remote is infrared and does not depend on the network being up in standby. It would also let Totum act as the remote for
  anything else with an IR remote (a soundbar, a fan), as one more step behind the one power seam (`DevicePower`).
- **A better USB Bluetooth adapter or an extender for the soundbar is the other half**: the Pi's Bluetooth link to the
  soundbar is the weak part of the speaker path (Soundmic, [ADR 165](../adr/0165-a-speaker-is-kept-awake-and-has-one-volume-its-own.md)).
- **The soundbar is already on HDMI ARC behind the TV** (the TV's inputs from pairing: HDMI_1 Fire TV Stick, HDMI_2 LG Sound
  Bar, HDMI_3 Chromecast, HDMI_4 unused), so the TV's own volume and power reach it; the Bluetooth path is for playing to the
  soundbar without the TV on.

## To refine later

Which blaster (a USB one on the Pi, or a Home Assistant one), whether Home Assistant is the control plane or Totum talks to
the blaster itself, which devices get an IR way, and what the "power on" step looks like next to wake-on-LAN and adb.
