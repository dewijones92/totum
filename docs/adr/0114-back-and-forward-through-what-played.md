---
title: Back and forward through what played
kind: adr
status: accepted
updated: 2026-11-13
---

# 114. Back and forward through what played

- Status: Accepted
- Date: 2026-11-01

## Context

Dewi, 2026-11-01: *"our queue has like a mix of music, podcasts, video … I want … back and forward, so I can go back
between the stuff I've been hopping around … it just keeps a history of what items I've been playing … so I don't
have to search within the queue."* The queue's ⏮ steps through queue order, not through what he actually played.

## Decision

- **`PlayTrail`** keeps the last 50 items that played, of every kind, like a browser's history: back and forward move
  along it, playing something new while back drops what was ahead, and an item played again moves to the end rather
  than appearing twice. It is kept across restarts (`PrefsTrailStore`, the backup format).
- **`TrailNavigation`** follows `PlaybackQueue.nowPlaying` and goes back or forward through the queue's ordinary
  paths: the item's own queue row if it is still queued (`jumpTo`, so nothing is reordered), else play-now. Each item
  resumes at its saved place like any other play.
- **"⬅️ Back · <title>" and "➡️ Forward · <title>" tiles** lead the full player's quick strip, each shown only when
  there is somewhere to go, naming where.

## Consequences

- Checked on totum-api35: two plays, Back returned to the first (row 12, unmoved) and offered Forward to the second.
- Not yet on the mini player or the notification; the player's strip is the one place for now.

## Amended 2026-11-13: hold the tile to see the whole trail

Press and hold "⬅️ Back" or "➡️ Forward" (the tiles say so in their accessibility label: "Back. Hold to see everything that
played") and a sheet lists the whole trail, up to 50 items, newest first, as ordinary item rows with the shared fuzzy
filter. The current place is tinted and says "📍 You are here". A tap goes there through `PlayTrail.goTo(index)` /
`TrailNavigation.goTo(index)`: like Back it plays that item, keeps every item ahead of it and appends nothing. Test:
`PlayTrailTest` (going to a place keeps what is ahead), `TrailListTest` (instrumented).
