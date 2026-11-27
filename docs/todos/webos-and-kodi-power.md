---
title: ⏻ Switch the LG off, put it on an input, and start Kodi on the Fire Stick when Android has killed it
kind: todo
status: built 2026-11-20 (ADR 177, proved against a fake SSAP server and a fake adb shell); real-device checks owed
priority: medium
updated: 2026-11-27
---

# ⏻ Switch the LG off, put it on an input, and start Kodi on the Fire Stick

Dewi, 2026-11-14: the LG (192.168.0.219) should be switched off from Totum and by a sleep timer, and a Kodi target that does
not answer should be started, because Android kills Kodi in the background on the Fire Stick (192.168.0.211). Decision and
design: [ADR 177](../adr/0177-a-device-says-how-it-comes-on-and-goes-off-lg-over-webos-kodi-started-over-adb.md). Feature:
[pi-plays-everywhere](../features/pi-plays-everywhere.md#power-how-a-device-comes-on-and-goes-off-adr-177).

## Reuse

`TvWake` became `SteppedPower` (one seam for wake-on-LAN, adb and display steps); `Shell` (`speakers/`) runs adb; `KnownDevice`
and `knownCastsFrom` name the devices; `KodiControl` is untouched; the sleep timer is `DeckSleep` with one more field; the
picker's hint is `PlayOnHint` with one more value; `airplay-pair`'s shape (a command, a 600 file under `$TOTUM_DATA`) is
`webos-pair`'s.

## Owed on the real devices

Nothing here has touched the real LG or the real stick. Dewi runs, once: `totum webos-pair "LG TV"` on the Pi, then ⏻ Switch
off, then a sleep timer with the checkbox ticked; and a play to Kodi after force-stopping it. Open questions only the devices
answer: that the LG accepts the signed registration manifest (the unsigned one is tried when it refuses), that it reports
`Active Standby` or `Suspend` while off, that `system/turnOff` is allowed for a PIN-paired key, and that the Pi's adb key is the
one the stick authorises.

## The `(1008)` line after a switch off (2026-11-27)

Done: a close the TV makes within two minutes of Totum's own successful switch off is logged at INFO as "the TV closed the
connection as it switched off" and does not mark webOS unavailable (ADR 177, amended 2026-11-27). Still to see on the real LG:
whether 1008 is what it sends every time, and how long after `turnOff` it comes.
