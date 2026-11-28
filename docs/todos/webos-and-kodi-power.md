---
title: ⏻ Switch the LG off, put it on an input, and start Kodi on the Fire Stick when Android has killed it
kind: todo
status: built 2026-11-20 (ADR 177, proved against a fake SSAP server and a fake adb shell); run on the real devices 2026-11-28, switch-off works, five defects fixed against the fakes the same day; the fixes are owed a real-TV run
priority: medium
updated: 2026-11-28
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

## Real-device run, 28 Nov 2026

Run on the real LG (paired) and the Fire Stick: the webOS switch-off worked, wake-on-LAN from deep standby did not, the stick's
sleep-then-wake did. Fixed against the fakes the same day (ADR 177, "Seen on the real devices"): the flapping power check, the
CEC wake of the TV through the stick, a volume with no link, Kodi's refused sign-in worded as such, and the false "LG TV on"
readiness line. **Owed on the real TV:** a Totum-sent CEC cycle waking the LG from deep standby (done by hand over adb
on the night), the log evidence on each power change showing why the check had flapped, `control VOLUME` on a dark `lg-tv` answering
with the `⚠️` problem, the Kodi 401 reading `🔑 Kodi refused the sign-in…`, and the Kodi password on the Pi (the 401 itself is a
credentials fault, not code).

## Owed on the real devices (earlier, before 28 Nov)

Nothing here had touched the real LG or the real stick. Dewi runs, once: `totum webos-pair "LG TV"` on the Pi, then ⏻ Switch
off, then a sleep timer with the checkbox ticked; and a play to Kodi after force-stopping it. Open questions only the devices
answer: that the LG accepts the signed registration manifest (the unsigned one is tried when it refuses), that it reports
`Active Standby` or `Suspend` while off, that `system/turnOff` is allowed for a PIN-paired key, and that the Pi's adb key is the
one the stick authorises.

## The `(1008)` line after a switch off (2026-11-27)

Done: a close the TV makes within two minutes of Totum's own successful switch off is logged at INFO as "the TV closed the
connection as it switched off" and does not mark webOS unavailable (ADR 177, amended 2026-11-27). Still to see on the real LG:
whether 1008 is what it sends every time, and how long after `turnOff` it comes.
