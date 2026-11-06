---
title: 📡 The Pi plays to any TV, browser or speaker; the phone is a remote
kind: todo
status: decided
batch: A (2026-11), phase 7
priority: high
updated: 2026-11-06
---

# 📡 The Pi plays to any TV, browser or speaker; the phone is a remote

Dewi 2026-11-06: *"flexible casting … cast to any speaker, any browser, and any TV … not dependent on my phone, or on
the phone being connected all the time"*. Confirmed: the Pi does the work, the phone only controls; targets are the
LG TV, the Chromecast Ultra, PC browsers and the Pi's Bluetooth speakers (not the Fire Stick); in Batch A.

Today the phone is the brain of every cast (ADR 68) and serves the PC screen (ADR 81); only the speaker queues
(ADR 116) already play from the Pi. Already shared with the Pi: YouTube resolving signed in (`:core:video`), search,
torrents and home copies, queue operations (`:core:data`), mpv playback. Phone-only today: Cast control (the Pi's test
scripts drove the LG with pychromecast, so the protocol is fine there), the stream builder in `core/playback/cast`
(about two thirds of its 41 files are plain Kotlin and can move to a JVM module; the rest uses MediaCodec), and the
wrapping of the sound processing in Media3 audio processors. The processing itself (`SilenceCutter`, `SpeechModel`, `SpeechTrack`, `LoudnessBoost`, `VolumeBoost`, `LookaheadLimiter`) has no Android imports and already runs on the JVM in the audio-quality tests, fed by ffmpeg.

Plan: (1) a spike on the Pi relaying 4K VP9 to the LG while cutting silence; (2) generalise the speaker queues into
one "📡 Play on…" picker of targets; (3) Cast TVs and browsers as targets fed by the Pi; (4) any phone or the PC page
controls them. Sound on Pi-fed targets, decided by Dewi 2026-11-06 (option B): exactly the phone's processing. Move the plain-Kotlin processing into a JVM module shared by the phone and the Pi (no second copy); on the Pi, ffmpeg decodes to PCM and re-encodes after it. The spike measures the Pi's CPU for the speech model alongside a 4K relay. Best quality only and Totum's own step-down (cast-best-quality-only) apply here too.

Whole-batch review: speaker queues and Jellyfin fold in here. Scout facts: 26 of 41 cast files are plain Kotlin (manifest, relay, rungs, probes, `ScreenLink`, `CastHttpServer`) and move to a shared JVM module in phase 1, before the cast fixes; `CastStreams` is a split; the Pi reads audio with ffmpeg (no pure demuxer); the silero weights keep one copy for both loaders; `screen.html` is static and `ScreenLink` is pure; Cast control on the Pi is delegated to pychromecast in the Pi's Python env (as playback is delegated to mpv), proposed. Dewi 2026-11-06: speakers move onto the same processed path (skip silence, boost), so the Pi has one sound engine; mpv stays the speaker's player, fed the processed stream (ADR 116 updated when built).
