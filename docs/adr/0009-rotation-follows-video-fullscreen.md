---
title: Video fullscreen follows rotation; the button locks landscape
kind: adr
status: accepted
updated: 2026-10-05
---

# 9. Video fullscreen follows rotation; the button locks landscape

- Status: Accepted
- Date: 2026-10-05

## Context

Dewi asked for a playing video to enter fullscreen when the phone rotates. Previously, only the
fullscreen button entered that mode, and it always locked landscape. Applying that same lock to
automatic entry would prevent the player observing a return to portrait.

## Decision

Track windowed, manual and rotation-triggered fullscreen as distinct modes. Automatic fullscreen
follows Android's configured orientation and leaves it unlocked; manual entry keeps the existing
landscape lock. The shell opens the same full player when a video is visible in landscape, so
rotation from the mini player reaches the same video surface. Audio and the Shorts reel retain
their existing screens. Explicit Exit/Back remains effective until another rotation or a change in video availability.

## Consequences

Portrait restores the expanded player after automatic entry. Device rotation lock is respected.
The existing fullscreen grace across video resolution/recovery still applies. Mode, entry and
exit are logged so a phone report can explain an unexpected transition.
