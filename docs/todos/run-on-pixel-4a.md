---
title: Make Totum run on a Pixel 4a
kind: todo
status: queued — Dewi 2026-10-14: stock Android 13 (not modded); investigate lowering minSdk to 33 when picked up
area: platform
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# Run on a Pixel 4a

Dewi, 2026-10-14: *"make the app work on pixel 4a"*.

## The blocker

- The Pixel 4a's last official update is **Android 13 (API 33)**; Totum's `minSdk` is **34** (Android 14),
  a deliberate decision recorded in CLAUDE.md's Decisions table (July 2026). So the APK will not install.
- The release APK is `arm64-v8a` only; the 4a is arm64, so that part is fine.

## Answered (Dewi, 2026-10-14)

"android 13 i think (i have not modded the pixel 4a) lets just queue the investigation." So the work is
lowering `minSdk` to 33, below. Not started.

## Questions for Dewi (asked)

- Which Android is the 4a on: stock Android 13, or a custom ROM (e.g. LineageOS) that is on 14+? On 14+
  nothing needs changing beyond trying it.
- If stock 13: lower `minSdk` to 33. That reverses a recorded decision (needs an ADR) and means auditing
  every API-34+ call: foreground-service types and `startForeground` rules, `canUseFullScreenIntent`
  (API 34), the Android 16 Live Update banner (already version-gated), partial media-permission
  handling, predictive back, and the exact-alarm permission flow.

## Done when

Totum installs and its main flows (playback, downloads, Exsurge, Daily alarms) work on the 4a, checked on
the device or on an API-33 emulator (`totum-api33`), with CI building for the new floor.
