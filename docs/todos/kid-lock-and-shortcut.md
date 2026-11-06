---
title: Lock a video for a child; a locked Peppa Pig shortcut
kind: todo
status: done 2026-10-22 (ADR 76)
area: player/kids
priority: high
requested: 2026-10-22
updated: 2026-11-06
---

# Lock a video for a child; a locked Peppa Pig shortcut

Android screen pinning (unpin with the phone PIN) plus Totum's controls hidden, fullscreen (Dewi's choice). A pinned home-screen icon with "lock on open" opens straight into that video fullscreen and locked, optionally continuing with its channel or playlist.

Done 2026-10-22: [ADR 76](../adr/0076-kid-lock-pins-the-app-to-a-bare-video.md). On the phone: turn on Settings → Security → App pinning, with "Ask for PIN before unpinning".

Sweep fix (2026-11-06): kid mode's 1x and no-silence-skipping are applied without saving them as the chosen talk speed
and skip-silence choice (`PlaybackController.setSpeedForNow`, `setSkipSilenceForNow`), so a process death while locked
loses nothing. `KidModeTest` asserts nothing was saved. Tests owed: a `Media3PlaybackController` check (instrumented)
that the saved speed in `PlaybackSpeedStore` is untouched across a lock and unlock.
