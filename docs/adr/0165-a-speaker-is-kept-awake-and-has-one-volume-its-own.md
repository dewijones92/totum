---
title: A speaker can be kept awake by the Pi, and its volume slider is the speaker's own
kind: adr
status: accepted
updated: 2026-11-13
---

# 165. A speaker can be kept awake by the Pi, and its volume slider is the speaker's own

- Status: Accepted (built and unit-tested; not yet seen on the real Soundmic)
- Date: 2026-11-13
- Amends: [ADR 116](0116-speaker-queues-play-on-the-pi.md) (a speaker is linked only while playing),
  [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md) (the volume rule),
  [ADR 158](0158-targets-end-of-batch-fixes.md) (`TOTUM_SPEAKERS_SWITCHED_ON_BY_HAND`)

## Context

Dewi, 2026-11-13: *"Soundmic: Pi keeps a connection so it doesn't sleep (the soundbar doesn't need it); two volume
controls problem (mpv vs the speaker's own)."*

- The dprofy Soundmic is switched on by hand and powers itself down after a while with nothing connected or streaming.
  The Pi unlinked a speaker as soon as its queue ended (ADR 116), so the next play found it asleep and the phone said
  "Switch it on, then try again" (ADR 158).
- A speaker had **two** volume controls that multiply: mpv's software volume (the phone's slider, ADR 152) and the
  speaker's own, set with its buttons or by BlueZ's AVRCP absolute volume (`org.bluez.MediaTransport1`'s `Volume`,
  0-127). The slider could say 30% while the speaker was at 5% or at 100%, and neither number was the level you heard.

## Decision

### Keep awake, a per-speaker setting

- `TOTUM_SPEAKERS_KEEP_AWAKE` in `fetch.env` names the speakers to keep awake, comma-separated, by name or id, each with
  an optional daily window: `Soundmic` (all day) or `Soundmic@07:00-22:30` (a window past midnight, `22:00-06:00`, is
  fine; an unreadable window is logged and means all day). **Unnamed speakers, including the soundbar, are not kept
  awake**: the setting is opt-in, so nothing changes for them.
- A kept speaker is **not unlinked when Totum is done** (`BluezLink.release` leaves it linked).
- `KeepAwakeKeeper` (per kept speaker, a fixed 30 s check) does this and nothing else:
  - outside its window: nothing is sent or reopened (state `OUTSIDE_WINDOW`);
  - not connected: `bluetoothctl connect` and a wait for the PulseAudio sink, retried after 2 min, then 4, then 8, capped
    at 10, so a switched-off speaker is not paged all night; the wait starts over when it comes back (`RECONNECTING`);
  - connected and nothing playing: two seconds of inaudible sound on the speaker's own sink about once a minute, so the
    link carries a live A2DP stream and the speaker's idle timer keeps being reset (`KEPT`); while Totum is playing
    nothing extra is sent.
- The sound is `TOTUM_SPEAKERS_KEEP_AWAKE_SIGNAL`: `silence` (default, digital silence, mpv's `anullsrc`) or `noise`
  (brown noise at amplitude 0.002, about -54 dB). Some speakers treat digital silence as no stream and still sleep; the
  noise is there for that case and **which one works on the Soundmic is not known yet**.
- `TargetDecks` runs the keepers every 10 s. With keep awake on anywhere, it also scans for targets once a minute
  (`warmDecks`), so a Pi that restarts with nobody asking still creates the Soundmic's deck and starts keeping it awake.
- Not configured means no keeper, no scan and no behaviour change at all.

### One volume: the speaker's own

- `SpeakerVolume` (every Bluetooth speaker, not only the Soundmic) turns the phone's 0-100 into the transport's 0-127
  (`HardwareVolume`), writes it to `org.bluez.MediaTransport1.Volume` with `busctl`, reads it back and **shows what the
  speaker reports**, since it may round to its own steps. mpv is left alone, and if an earlier fallback left it below
  100%, a phone volume change puts it back to 100% so there is one control.
- **Fallback, logged with its reason:** when there is no A2DP transport (not connected, or not streaming yet), no `Volume`
  property (no absolute volume support), a refused write or an unreadable answer, the slider drives mpv's volume as
  before and the state says so (`VolumeMode.SOFTWARE`).
- **Keeping the volume low:** only a `VOLUME` action from the phone ever writes a volume, as in ADR 152. Reading is
  separate (`refresh`, after the link comes up, every 30 s tick while playing, and on each keeper check) and never
  writes. A read that finds mpv still holding an earlier level does not raise it: the phone shows that level and the
  next slider move makes the speaker's own the one control. A play, a reconnect, the keep-awake sound and a restart set
  nothing; the Pi's mpv volume is forgotten on restart, so the speaker's own is then shown. The shown level also
  follows the speaker's own buttons.
- `TargetState` gains `volumeMode` (`HARDWARE` or `SOFTWARE`, absent while unknown) and `keepAwake` (`KEPT`,
  `RECONNECTING`, `OUTSIDE_WINDOW`, absent when not kept). Both are live and never saved with the queue. An older phone
  ignores them; an older Pi does not send them and the phone shows its old volume line.

### What the phone shows

In the target's remote panel, emoji first: "🟢 Kept awake, <name> stays connected", "⏳ Keeping <name> awake,
reconnecting…", "💤 <name> is not being kept awake just now (outside its hours)"; and the volume line says whose it is,
"🔈 Volume 40% · the speaker's own" or "🔉 Volume 40% · Totum's, the speaker's own is not available", or "🔈 Volume not
known" until a level is known.

## Consequences

- **Logging** (`dewidebug`-style `Diag` lines, counted, never per tick): every keep-awake state change with its reason;
  a summary every 20 checks (signals sent and failed, reconnects tried and failed, waits, checks while playing); every
  volume decision with what was asked, the raw value, what the speaker reported and why a fallback happened; the
  `busctl` write; a changed reading is logged once, not on every read.
- **The image needs `busctl`**, which Debian and Ubuntu ship in the `systemd` package, so `server/totum-cli/Dockerfile`
  installs `systemd` beside `bluez` (the system D-Bus socket is already mounted). `busctl` was not available in the
  image before; this is the one new package.
- **Deploy:** add `TOTUM_SPEAKERS_KEEP_AWAKE=Soundmic` (and optionally `TOTUM_SPEAKERS_KEEP_AWAKE_SIGNAL=noise`) to
  `/var/lib/totum-fetch/fetch.env`, rebuild `totum-fetch` from the release. Compose needs nothing else. Any order with
  the phone.
- **Tested with fakes:** the policy (a fake transport and clock: rate limits, the back-off and its reset, windows,
  busy, a failed signal), the volume logic (a fake transport: hardware, fallback, read-only refresh, the speaker's
  rounding), the `busctl` commands (a fake shell), the link's release, reconnect and silence command line, the deck's
  routing and the wire. Seen failing against four mutations (release still unlinks a kept speaker, the deck still sends
  the slider to mpv, a keeper that signals while playing, mpv not put back to 100%).
- **Not seen on a real device:** that `busctl tree org.bluez --list` shows the transport at the time the phone moves the
  slider (BlueZ may make it only once a stream is configured), that the Soundmic accepts an absolute `Volume`, that mpv's
  silence on the sink keeps its idle timer from running out, and whether `noise` is needed. The first three decide
  whether the Soundmic runs in `HARDWARE` or `SOFTWARE`/falls asleep; the state and logs say which.
- A speaker switched off is still not woken (Bluetooth cannot do that); the keeper reconnects it when it is switched
  on again and the speaker itself reconnects first in most cases.
