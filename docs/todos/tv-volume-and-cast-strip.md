---
title: TV volume from the phone; the strip says what is cast
kind: todo
status: built; tests written
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# TV volume from the phone; the strip says what is cast

Dewi 2026-11-05. Volume buttons and the player's volume gesture drive the TV while casting (`RemoteCastPlayer`
has `setDeviceVolume`; `TimeMappedCastPlayer` forwards it), plus a slider in the cast strip. The strip says the
quality and codec sent, e.g. "📺 1440p VP9 on LG OLED".

Built 2026-11-06: `PlaybackState.castVolume` (level, max, muted) from the session's remote device volume;
`PlaybackController.setCastVolume(fraction)` sets it through `MediaController.setDeviceVolume`; the cast strip shows
"🔊 Volume 35% on LG" with a slider; the fullscreen volume swipe drives the TV while casting and its readout says
"📺 TV volume". Hardware volume keys need no wiring: Media3's session (`MediaSessionLegacyStub`, checked in 1.10.1's
bytecode) hands the system a remote `VolumeProviderCompat` whenever the session's player reports a remote device, which
the cast player does, so the keys reach the TV while the app's session is the active one. Not seen on a device.

Tests owed *(written in part 2026-11-14: see "Tests written 2026-11-14" below for what is done and what is still owed)*: `CastVolume` (levelFor rounding and clamping, `of` with max 0), a `PlaybackState` mapping test with a fake
controller, `VideoGestureState` with a TV volume (drag up raises the TV, the phone's volume untouched), the cast strip
slider (`CAST_VOLUME_TAG`) calling `setCastVolume`. Device check on the LG: the slider, the swipe and the hardware keys
move the TV; unplugging the cast hands the keys back to the phone.

## Tests written 2026-11-14

`CastVolumeTest`, `VideoGestureTvVolumeTest`, instrumented `CastVolumeRowTest` (the readout, muted, the slider asking the TV). Owed: the `castVolumeOf` mapping from a `MediaController` and the device check on the LG.
