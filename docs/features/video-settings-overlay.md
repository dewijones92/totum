---
title: Quality and speed as an on-video overlay
kind: feature
status: shipped
area: ui
updated: 2026-11-06
---

# Quality and speed live on the video

**Ask (Dewi):** "the quality selectors in pipepipe is a dropdown on the video as a
transient overlay thing — I prefer that to what they are currently in our app ...
selectable beneath the video".

Quality and speed are now buttons in the video's auto-hiding control overlay, top-right,
each opening a compact menu **over the picture**. The row of quality buttons that used to
sit below the player is gone.

## Why it's the better place, beyond taste

**In fullscreen there is no "beneath the player".** A control that lives below the video is
a control you lose exactly when you most want it — mid-video, full screen. Putting these in
the overlay means one implementation serves inline *and* fullscreen, because the overlay is
the same composable in both.

## What moved where

| Control | Before | Now |
|---|---|---|
| Quality | Scrolling button row beneath the video | Menu on the video (video only — audio has no resolutions) |
| Speed | Button row beneath, for everything | Menu on the video for video; **the row stays beneath for audio** |
| Sleep timer, skip-silence, auto-play, volume boost, Listen⇄Watch | Beneath | Unchanged |

Speed keeps its inline row for audio deliberately: a podcast has no video overlay to hang
it on, and speed matters more for podcasts than anything else in the app.

## One duplication caught on the way

Writing the menu, I gave it its own list of playback rates — a second copy of something
`PlaybackPreferences.kt` already had, with different values *and* a different label format.
That is exactly the drift the project's DRY law exists to stop, so the rates and their
label now live in one place (`PlaybackSpeeds`, `speedLabel`) and both surfaces read it. The
merged list reaches 3× because a podcast at 3× is a real use where a video rarely is.

The selected item is marked with a **dot, not a tick**: a tick beside a row's label reads
as a checkbox list, i.e. as though several could be on at once.

## Verified on-device

Played a YouTube video, opened the overlay, and drove it through `uiautomator`:

- The overlay composes `Close`, `Quality`, `1x`, transport, and `Fullscreen`.
- The quality menu opens over the video with the full ladder — 2160p, 1440p, 1080p, 720p,
  480p, 360p, 240p, 144p — the current one dotted.
- Selecting **720p** moved the dot and playback continued (`state=PLAYING`), rather than
  restarting or stalling.

Worth knowing for future device checks: the overlay auto-hides a few seconds into playback,
and each tap on the video *toggles* it, so a `screencap` taken after a `sleep` usually
catches an empty frame. Dump immediately after the tap, with no sleep, or pause first.

## Brightness / volume slide gestures (2026-07-27)

Slide vertically over the picture: left half changes **brightness**, right half
**volume**, with a centred readout (icon, bar, percentage) while the drag lasts. The
gesture every serious Android player has — PipePipe, mpv, VLC — and what makes fullscreen
usable without reaching for the system bars.

On the shared `VideoStage`, so it works inline and fullscreen from one implementation.
Which half the drag *started* in fixes what it controls for the whole gesture; deciding
per-movement would flip control as a thumb drifts across the middle. A full swipe of the
stage covers the full range, so it feels the same at either size. Brightness is restored
to the system value when the video goes away, or the whole app would stay dimmed.

Ordering matters: the gesture modifier sits **before** `clickable`, because the tap
detector otherwise claims the pointer stream and the drag never arrives — that cost a
build to find.

Verified on device: right-half down-swipe took volume 15 -> 8 with the readout showing
0.533 (= 8/15); left half reports BRIGHTNESS and tracks 1.0 -> 0.279. The panel dimming
itself is not observable on an emulator.

## Video fills the width in portrait (2026-07-27)

The player column padded everything by 24dp, including the video, so the picture sat
inset from both edges — measured at `[63..1017]` on a 1080px screen. The column now pads
vertically only and the text below carries its own margin, so the video is full-bleed:
`[0..1080]`.

## The codec beside each quality (2026-11-06, [ADR 127](../adr/0127-downloads-honour-the-phones-decoders-and-qualities-name-their-codec.md))

The quality menu reads "1080p · AV1", "1440p · VP9" (`QualityLabel`, the same label the cast strip uses). The ladder
offers one codec per height, the one the device's decoders rank first (hardware AV1, then VP9, HEVC, H.264; never
software AV1), so the label says which one was chosen. A stream whose codec is unknown stays "720p".
