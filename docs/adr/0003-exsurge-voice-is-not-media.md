---
title: Exsurge's voice is an alarm, not media
kind: adr
status: accepted
updated: 2026-10-01
---

# 3. Exsurge's voice is an alarm, not media

- Status: Accepted
- Date: 2026-10-01

## Context

The Unified law says there is one playback path, `PlaybackController`. Surgius's voice clips
("Exsurge!", "Liber es!") also make sound, so they look like a second path.

## Decision

The clips play through their own small `MediaPlayer` queue (`VoiceCues`), on
`AudioAttributes.USAGE_ALARM`, with transient ducking audio focus. They do **not** go through
`PlaybackController`.

## Why this is not a duplicate

The clips are alarm sounds, not media. They must never enter the queue, the media session, the
notification's transport controls, history or progress sync. And they must play while the media is
paused, which is the very thing the break does to `PlaybackController`.

## Consequences

- The clips use the alarm volume, so the settings offer a volume and a "quiet office" mode.
- The clips are generated once by `tools/exsurge/gen-voice.sh` (edge-tts, Italian *Diego* reading
  Latin), committed as mono Opus (~130 KB in total), and need no network at runtime.
