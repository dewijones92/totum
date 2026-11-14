---
title: Triage of the AI feature-gap review
kind: todo
status: triaged 2026-07-27 — seven of the nine "Agree" rows are marked DONE 2026-07-27; the played/unplayed flag and private feeds carry no outcome in this file
area: planning
updated: 2026-11-14
---

# Triage: which of the AI review's gaps actually make sense

Dewi pasted a Gemini review of gaps vs AntennaPod and PipePipe/NewPipe and asked which
hold up. Checked against the code rather than taken on trust. Verdicts below; the two
claims worth verifying were both **accurate** (SponsorBlock categories really are the
hardcoded `listOf("sponsor", "selfpromo", "interaction")`; the sleep timer really is
fixed-minute options only).

## Agree — do these

| Gap | Verdict | Note |
|---|---|---|
| **Picture-in-Picture** | **DONE 2026-07-27.** Was the strongest item on the list. Genuinely missing and it's the one thing that makes a video app feel unfinished on Android | `enterPictureInPictureMode` + a PiP-shaped player. Video-only by nature, which is a legitimate pillar asymmetry (a podcast has no picture) |
| **End-of-item sleep timer** | **DONE 2026-07-27.** | `SleepTimer` takes a `Duration`; this needs an end-of-item mode instead, which the queue's end-transition already detects |
| **Played / unplayed / in-progress state + "hide played"** | Agree — real AntennaPod parity gap | `playback_progress` already knows the position; what's missing is an explicit *played* flag and a feed filter. Unified: applies to videos just as well |
| **Full backup / restore** | **DONE 2026-07-27.** | OPML covers subscriptions only; playlists, history, queue, resume points and settings can't leave the device. One JSON/zip over the Room tables |
| **Granular SponsorBlock per category** | **DONE 2026-07-27.** | Categories are a hardcoded list; per-category action (skip / show / ignore) is a settings screen over data we already fetch |
| **Player gestures (brightness / volume)** | **DONE 2026-07-27.** | Fits the fullscreen player; pairs naturally with the UI-polish work |
| **Channel search** | **DONE 2026-07-27.** | InnerTube channel browse already works; search-within-channel is another `browseWeb` params value |
| **Authenticated / private feeds** | Agree it's missing | Basic-auth podcast feeds. Only worth it if you actually have a private feed — tell me if so |
| **Storage breakdown in Library** | **DONE 2026-07-27.** The auto-download work made it *needed* — a queue that downloads everything must show what it's using | |

## Disagree / already handled

| Claim | Reality |
|---|---|
| "Aspect ratio stretch/crop toggle missing" | The **stretch bug** is fixed (fullscreen letterboxes correctly). A *fit/fill/zoom* toggle is a reasonable extra, but it's a nice-to-have, not the parity gap implied |
| "Subtitles/captions missing" | Correct, but it was already specced by us before the review — see [subtitles-captions](subtitles-captions.md) |
| "Android Auto / Wear via `MediaLibraryService`" | Real, but I'd put it **last**. It's a structural change to `PlaybackService` for a surface you may never use. Ask yourself if you drive with this app before I spend a day on it |
| Auto-cleanup rules ("delete played after N days") | Reasonable in AntennaPod, but it **contradicts your decision** that nothing is auto-deleted. Worth revisiting only if storage actually bites |

## What the review missed

- **Drag-to-reorder in the queue** (you asked separately) — currently up/down arrows.
- **Download notifications** (you asked separately) — AntennaPod/NewPipe show progress
  in the notification shade; we download silently.
- **A proper app icon** (you asked separately).
- **The >1080p playback bug** — a live defect worth more than most items here, now
  fixed: [high-quality-playback-fix](high-quality-playback-fix.md).
- **Two-way progress sync** — the review assumed sync exists; it's one-way, see
  [youtube-progress-two-way-sync](youtube-progress-two-way-sync.md).

## My order, if it were mine to choose

1. **PiP** — biggest felt gap.
2. **Subtitles/captions** — already specced, one seam serves both pillars.
3. **End-of-item sleep timer** + **download notifications** — both small, both visible.
4. **Played/unplayed + hide-played** — real parity, moderate work.
5. **Storage breakdown** — now that the queue downloads everything.
6. **Backup/restore**, **SponsorBlock per category**, **gestures**, **channel search**.
7. **Android Auto** — only if you'd actually use it.
