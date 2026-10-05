---
title: Colour-video pixel probe on the API-35 emulator
kind: investigation
status: unresolved
updated: 2026-10-05
---

# Colour-video pixel probe

The rotation behavior tests pass with the repository's standard black video clip. An additional
colour-pixel proof failed locally, including a standalone Media3 `PlayerSurface` with Totum's layout
removed and before any rotation. Its root cause is unconfirmed: SDK, emulator, fixture and test
harness remain possible. This does not establish a regression caused by the rotation change.

The probe records actual playback advancing, a first-frame callback, and an accelerated window.
TextureView was shown, available, alpha 1, and correctly sized (1920×1080 in landscape), but its
bitmap was black. SurfaceView also failed the standalone colour check. A software AVC decoder,
ANGLE/SwiftShader with Vulkan disabled, and an explicit TextureView binding did not resolve it.
The binding experiment was reverted; the production surface continues to use Media3's existing
TextureView. No rendering workaround was shipped.

`VideoPixelsOnThisDeviceTest.kt` and `rotation.mp4` preserve the failed investigation outside the
normal test source set. To reproduce in an isolated checkout, copy the Kotlin file into
`app/src/androidTest/java/com/dewijones92/totum/ui/` and the clip into `app/src/androidTest/assets/`.
Build the app/test APKs and install both with `adb -s emulator-5554 install -r`; run the class through
`com.dewijones92.totum.test/com.dewijones92.totum.TotumTestRunner`. Preserve the existing YouTube sign-in.

The synthetic clip was generated using FFmpeg `testsrc2`, 320×180 at 5 fps for 30 seconds, H.264
baseline (`-tune zerolatency -bf 0 -g 5 -crf 25 -pix_fmt yuv420p`), plus silent stereo AAC at 48 kHz.
It contains no user data. The probe's UI state is synthetic; the actual player is ExoPlayer.

The feature's automatic entry/exit, manual entry, explicit exit, recovery and audio behavior have
separate passing coverage. Colour pixels on this emulator remain unverified and should be revisited
in the requested GPT-6.1 Astra review or on a device capable of reproducing the probe reliably.
