---
title: Kid TV: one tap casts a pinned playlist to a chosen TV in kid mode, and the phone stays free
kind: adr
status: accepted
updated: 2026-11-07
---

# 148. Kid TV: one tap casts a pinned playlist to a chosen TV in kid mode, and the phone stays free

- Status: Accepted
- Date: 2026-11-07

## Context

Dewi, 2026-11-05 (agreed idea): one button casts a chosen kid playlist to the LG in kid mode (1x, no silence skipping, no
surprises; [ADR 76](0076-kid-lock-pins-the-app-to-a-bare-video.md)), checked with tennis and never kids' videos on his
TV. ADR 76's kid mode was tied to the phone's own lock (`KidLock.locked`): a bare full-screen video, touches and back
ignored, app pinning. On a TV the phone is the remote and the adult's device, so locking it would be wrong, but the
playback rules (speed, silences, loop, no Explore) are exactly what the child's TV needs.

## Decision

- **Kid mode has two doors.** `KidLock` gains `tv` (the TV's name while Kid TV is on) and `modeOn` (locked or tv).
  Every place that applied the kid *rules* now reads `modeOn`: `KidRules` for repeat, auto-play and Explore, `KidMode`
  (1x and no silence skipping, restored afterwards, never saved as a choice), the active-modes row, the gapless armer, the
  repeat-one rule, the playback context and the bedtime timer (which does not arm in kid mode). Everything that is the
  phone's *lock* (the bare stage, the pin, the shell's back handling) still reads `locked`. The mode chip says
  "👶 Kid mode" for both.
- **The choice is a pin and a TV name.** A pin because the ADR 76 locked shortcut already uses "any pin that can show a
  picture" and `PinPlayer` already knows how to play every kind of pin as one queue group, which is what the loop needs.
  The TV is the Cast device's friendly name. `KidTvChoice` is stored in its own preferences file and rides in the Drive
  backup (`kidtv.v1`).
- **`KidTv` is the one orchestrator**, with ports (a `TvConnector`, the cast device flow, "play a pin", pause, end the
  cast) so it is tested without Android. A tap starts when ready and stops when on: connect to the TV (skipped when the
  phone already casts to it), wait for the cast hand-over of whatever was playing to settle, switch kid mode on, then play
  the pin. A failure at any step leaves kid mode off and says why, typed (`KidTvFailure`: playlist gone, TV not found with
  the TVs that were seen, no local-network access, the cast would not start, the playlist would not start).
- **Stopping pauses, waits 0.8 s, then ends the cast** so the hand-back to the phone does not resume the playlist in a
  pocket. The cast ending or moving to another TV by itself leaves kid mode without touching anything.
- **`MediaRouterTvConnector`** finds the route by name with an active scan (20 s), selects it, and waits for the cast
  session to start (20 s). It is the production form of the debug receiver's `castTo`. `CastTarget.device` now publishes
  the connected device's name.
- **One tap, and a page for the choices.** A Library tile, "👶📺 Kid TV", starts or stops with one tap, shows what it is
  doing in the ordinary status words, and has a gear for the page where the playlist and the TV are chosen (also in
  Settings, searchable). The start runs as a progress-strip task ("⏳ Starting … on …", Cancel) and ends with a toast.

Dewi's words cover the idea, the TV, kid mode and the tennis rule. The builder's calls, open to change: a pin as the
"playlist", the tile and page as the surface, leaving the phone unlocked, the 0.8 s settle before ending the cast, the
20 s waits, and not waking the TV.

## Consequences

- The TV must already be on, or in a state where it still advertises itself for casting. Totum does not wake it: the
  wake-on-LAN address is not known to the app, and a missing TV is reported with what was seen.
- Not run against a real TV in this change (no device was touched). The first real run should check that selecting the
  route by name starts the session on the LG, that the cast hand-over wait is long enough, and what the phone's own
  Exsurge, alarms and recorder do to a running Kid TV: a pause from any of them also pauses the TV.
- A phone running Kid TV that is also kid-locked is unchanged.

## Tests

`KidTvTest` (13): not set up, the happy path (kid mode on before the first item, the phone not locked), already casting
there, TV not found (with what was seen), no local-network access, a playlist that will not start, a playlist no longer
pinned, stopping (pause then end the cast), the cast ending or moving by itself, choices and restore, the codec, and
`modeOn` for lock and TV together.

## Tests still to write

A Compose test of the tile and page; `MediaRouterTvConnector` against a fake `MediaRouter`; the debug broadcast to start
Kid TV for a device run; a check on the LG, daytime, with a tennis playlist, with the existing permission to wake it and
cast at low volume.
