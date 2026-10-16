---
title: The player's title has the whole width; the menu and Cast are in the top bar
kind: adr
status: accepted
updated: 2026-10-16
---

# 67. The player's title has the whole width; the menu and Cast are in the top bar

- Status: Accepted
- Date: 2026-10-16

## Context

A report from 0.1.643 (Pixel 7): "can things here be better designed or arranged? for example, the title seems a bit
squished". The title shared its row with the kind badge, ⋮ and Cast, so a song title wrapped to five lines in half
the width, while the top bar held only ✕ and a 48 dp gap sat above the seek bar. Dewi chose the top bar for ⋮ and
Cast, the badge among the facts, and three tidy-ups: close the gap, say Music for songs, keep the quick strip on screen
for audio.

## Decision

- `TitleBlock` is one full-width column: artist, title, album, the kind badge, then the facts.
- `PlayerTopActions` (Cast, ⋮) sits in the top bar beside ✕ for audio, and in the video overlay's top corner beside the
  video settings (white Cast icon there, `CastButton(onDark = true)`); not in fullscreen, whose chrome is hidden.
- The badge says 🎵 Music for `MediaContentKind.MUSIC` (`PillarBadge(music = …)`).
- Audio: the gap above the seek bar is 24 dp, and the artwork is capped at 30% of the screen height (and 320 dp), so
  the quick strip is on screen without scrolling.

## Consequences

- The Cast button is an Android view inside Compose and is given a fixed 48 dp: unsized, it stretched across the
  overlay's top row and swallowed the taps meant for ✕, ⋮ and the video settings in 0.1.644
  (`TheVideoOverlayOffersTheMenuTest`, which passes against 0.1.641 and failed until the size was set).
- The badge's glyph rule (`pillarIcon(kind, music)`) is shared with the mini player.

- On a video the menu and Cast hide with the overlay's other controls; a tap brings them back.
