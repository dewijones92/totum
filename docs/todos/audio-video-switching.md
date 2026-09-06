---
title: Easy audio ↔ video switching for a queue item
kind: todo
status: shipped — the switch and the toggle both work; merging a LOCAL audio copy with video is the open part
area: playback
priority: high
requested: 2026-07-25
updated: 2026-09-06
---

# Switch between audio and video, carrying the position

> **Status corrected 2026-08-06.** This file said `refining` while the backlog index said
> "shipped (local-audio merge outstanding)" — two statuses for one item, which is how a stale status
> starts. The switch, the toggle and the position-carrying all shipped; what remains is the narrow
> case below.

**Ask:** the queue auto-downloads audio for everything — but when I tap a queue item
(playing or not), ask me whether I want to **switch to video**; if yes, carry on from
the same point. Warn in the same dialog if I'm on mobile data. Also put a **toggle**
in the item while I'm actually in it.

Depends on [queue-first-playback](queue-first-playback.md) and
[auto-download-queue](auto-download-queue.md).

## Most of this already exists

`VideoPlaybackLauncher` already switches a *resolved* video between audio-only and
video and resumes from the same point, and the full player already shows the toggle:

- `listen()` — replays the same item with the audio-only stream (no video track, so
  the player shows artwork; far less data).
- `watch()` — returns to the video ladder.
- Position is preserved because `Media3PlaybackController.play` restores from
  `PlaybackProgressStore` on every play, so a switch resumes where you were.
- `ListenWatchToggle` in the full player shows when `canListen && (hasVideo ||
  listening)` — added when the "listen-mode trap" was fixed.

So the **toggle while you're in an item is largely done**; what's missing is the rest.

## What's actually new

1. **The prompt on tapping a queue item.** A dialog: "Playing audio — switch to
   video?" with Yes / Keep audio, plus a **mobile-data warning line** when
   `NetworkStatus` says we're not on Wi-Fi (`NetworkStatus` and per-network quality
   prefs already exist). Needs a "don't ask again" so it doesn't nag.
2. **Switching to video from a *downloaded audio* item.** Today's toggle only works
   for a video resolved this session (the launcher holds `current`). A queue item
   played from a local audio file has no resolved video, so "switch to video" must
   resolve the watch URL fresh and seek to the saved position. That's the real work
   — and it's also the piece that makes the toggle meaningful for offline items.
3. **Best-of-both playback (worth considering).** If the audio is already downloaded,
   switching to video should stream **video-only** and keep using the local audio
   file — the player already merges a separate audio track with a video-only stream
   (`EXTRA_AUDIO_URL` in `Media3PlaybackController`, used for the high-quality
   ladder). Extending that to accept a local audio *path* would make "switch to
   video" cost only the video bytes. Nice fit; needs verifying the service accepts a
   file URI as the merged audio source.

## Decided (Dewi, 2026-07-25)

- **No prompt on every tap.** Tapping plays audio; a clearly visible toggle does the
  switch; the mobile-data warning appears only when you actually switch to video on
  mobile data. (Dewi: "lets do it yourway".)
- **The toggle lives in both places** — the row's long-press sheet (so it lands on
  every feed, both pillars) *and* the Queue tab, alongside the existing one in the
  full player.
- **Stickiness is a global mode, not per item.** Dewi's instinct, and it beats the
  per-item idea: wanting audio is *situational* ("I'm washing up"), not a property of
  a particular video. Playback speed is per-source because a slow talker is a
  property of the source; audio-vs-video isn't.

## The mode (proposal)

`PlaybackMode` in `AppPreferences`, persisted across restarts, three states:

| Mode | Behaviour |
|---|---|
| **Auto** (default) | Video on Wi-Fi, audio on mobile data — the "smart" default, which also makes the data warning nearly redundant |
| **Audio** | Everything plays audio-only, preferring the downloaded audio |
| **Video** | Videos play with picture |

Consulted in exactly one place — `VideoPlaybackLauncher`, when it decides between the
video ladder and the audio-only stream — so it covers every screen and is a no-op for
podcasts (no video track to choose).

**Honesty about the global effect:** a row-sheet action that silently changes global
state reads oddly ("why did this row's menu change everything?"). So the sheet action
plays *that* item the chosen way **and** sets the mode, and says so — a snackbar
("Video mode on"). One concept, no hidden per-item state, and no settings-screen hunt.

## Settled (Dewi, 2026-07-25) — spec complete

- **Auto is the default.**
- **Shorts and an explicit fullscreen tap force video for that item only**, leaving
  the mode alone — and a **toast says so** ("Watching this one — audio mode kept"), so
  a one-off never looks like a mode change.
- **Cast is out of scope for now**: no special-casing, and mode behaviour while
  casting is left unverified rather than half-built. Revisit with real hardware.
- **Switching to video reuses the downloaded audio**: stream the **video-only** track
  and merge the local audio file, so the switch costs only video bytes. The player
  already merges a separate audio track for the quality ladder
  (`EXTRA_AUDIO_URL`) — this extends it to accept a local file path.

**Risk to check first:** that the playback service actually merges a `file://` audio
source with a remote video-only stream. If Media3 baulks, fall back to the normal
muxed stream for that case and say so rather than shipping something flaky.

Spec is complete — implementation waits on Dewi's go (it sits on top of
[queue-first-playback](queue-first-playback.md)).

**Done when:** tapping a queue item and switching to video (and back) continues from
the same position, the data cost is made clear before it's spent, and the switch is
reachable both in the player and from the queue.

## Shipped 2026-07-25 — the mode

- `PlaybackMode` (**AUTO** default / AUDIO / VIDEO) in `AppPreferences`, persisted.
- Resolved in `AppContainer` (the only thing that knows about the network — AUTO means
  video on Wi-Fi, audio on mobile data) and handed to `VideoPlaybackLauncher` as a
  single `audioPreferred()` question. The launcher consults it in **one** place, so the
  mode holds no matter which screen started playback.
- The player's Listen/Watch toggle now **sets the mode** rather than only affecting the
  current item; `watchOnce()` exists for the one-off case.
- Row action on the Videos feed: "Listen only" / "Watch with video", which switches
  **and announces it** ("Audio mode on"), because a row action silently changing a
  global setting would be baffling.
- **Shorts force video** for that item with the mode left alone, and say so via a toast
  ("Watching this one — audio mode kept").

### Verified on-device

Switched a video row to "Listen only" → `playback_mode=AUDIO` persisted, playback had
**no video decoder** (`hasVideo=false`, aac only). Tapped a **different** video → still
audio-only, proving the mode is global rather than per-item. Switched back with "Watch
with video" → `hasVideo=true`, h264 decoder, `playback_mode=VIDEO`.

### Still to do

**Reusing downloaded audio when switching to video** (the piece you picked over the
muxed shortcut): stream the video-only track and merge the *local* audio file. The
merge machinery exists (`EXTRA_AUDIO_URL`), but it is typed as an `HttpUrl`, so it
needs to accept a local path — and then the "does Media3 merge a `file://` audio source
with a remote video stream?" question gets answered for real. Falls back to the normal
muxed stream if it won't.

## The way back to the picture (fixed 2026-09-06)

An item that STARTED in listen mode — the everyday case with the queue's audio downloaded and Listen
on — showed no Listen/Watch toggle in the full player, so there was no way back to the video. The
toggle hides when `QualityState.canListen` is false, and `canListen` was only ever written by the
watch path (`playVideoQuality`); `listen()` inherited whatever was there, which after the per-item
reset was `false`. `listen()` now writes `canListen = true` — true by construction, since it has just
found the audio-only URL. By-construction fix, no dedicated unit test: the launcher needs a real
`VideoResolver` to reach that line, and the toggle's own visibility rule is already covered by
`PlayerKeepsEveryControlTest`.
