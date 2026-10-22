---
title: Drop to audio when Wi-Fi drops mid-video
kind: todo
area: playback
priority: high
status: shipped — unit-tested, and proven on a device with the radios actually toggled
updated: 2026-10-22
---

# Drop to audio when Wi-Fi drops mid-video

Dewi, 2026-08-04: *"if the phone is playing video but then there is suddenly no wifi, a notification
appears saying 'hey we have switched to listening only mode'"*.

Confirmed as the **metered** reading: the phone falls off Wi-Fi onto mobile data. (The other
reading — no network at all — is a different feature: audio needs the network too, so the honest
response there is "playing your downloads", which is the offline-skip path in
`offline-queue-e2e.md`.)

The saving is why it is worth doing at all: **15.2 MB/min** for a whole stream against **2.1** for
the audio alone, measured on the Pi. Walking out of the house with a video playing otherwise spends
mobile data at eight times the rate, silently.

## It needed almost no new machinery

Every part already existed — listen mode, an audio-only route per pillar (a YouTube audio stream, an
HLS playlist the home server remuxes), `replayCurrent` to switch without losing your place, and
`NetworkStatus.isMetered()`. `MeteredAudioSwitch` decides *when*; it knows nothing about pillars,
which is the unified seam paying off rather than three implementations.

It flips the real playback mode, so the player's own toggle is the undo and the choice carries to
the next item as anyone would expect.

## Hysteresis is the whole risk

A connection bouncing between Wi-Fi and mobile — a lift, a train, the end of the drive — would
otherwise re-prepare the player every few seconds and stutter playback continuously. Mobile has to
hold for **15 seconds** before anything happens, which turns flapping into a no-op. That case has
its own test, because it is the one that decides whether the feature is tolerable.

Sampled on a clock rather than collected, for the same reason `StallWatchdog` is: connectivity is a
level, and the interesting case is a state that persists.

## Decisions taken

- **Coming back to Wi-Fi does NOT restore video.** Dewi's call: a screen lighting up with video
  nobody asked for is worse than staying put, and it is one tap away.
- ~~**Once per item**~~ **Once per stretch of mobile data** (changed 2026-10-22), re-armed by returning to
  Wi-Fi, so a second trip out switches again. Per item was wrong: report 20261017T082929 (0.1.645) had Dewi put
  the picture back on for a Miss Rachel video on mobile data, and the next video was switched to audio again 17 s
  later, because the stretch had long passed the hold: *"it kept weirdly switching back to audio"*. Putting the
  picture back by hand now holds until Wi-Fi returns. The per-item "keep video" override this replaced was never
  wired to anything; only its tests called it.
- **A failed switch is never announced.** Video is still running, and saying otherwise would be a
  lie in a notification.
- **Its own notification channel**, so it can be silenced alone. A notification you cannot turn off
  separately is one people turn off entirely, taking the useful ones with it.

## Proven on a device, 2026-08-04

`MeteredAudioSwitchDeviceTest` plays a real clip with a real video track and turns Wi-Fi off. The
emulator carries BOTH a mobile and a Wi-Fi network, so `svc wifi disable` falls back to mobile
exactly as a phone does when you walk out of the house — a change of network, not a loss of one.

Two cases, and the pair is what makes it meaningful: mobile that HOLDS switches the mode to AUDIO,
and a six-second blip changes nothing. Either alone would be satisfied by a broken implementation
(one that always fires, or one that never does).

It also settled the assumption underneath the whole feature: **the emulator's mobile network really
is reported as metered.** If it were not, `isMetered()` would be false and nothing would ever fire —
the feature would have looked fine in unit tests and done nothing on a phone.

The clip is a 90-second black 320x240 H.264 with silent audio, ~69KB, generated with ffmpeg rather
than sourced, so the repository carries no real media.

**What this does NOT prove:** that less data is used. The clip is local, so nothing is downloaded
either way. The saving was measured separately (15.2 MB/min against 2.1); what was unproven was the
machinery, and that is what runs here.

## Still to do

- A **"keep video"** action on the notification itself. `MeteredAudioSwitch.keepVideo` exists and is
  tested; nothing calls it yet, because switching back through the player's toggle already prevents
  a re-downgrade (the item is marked switched until Wi-Fi returns).
