---
title: Auto-play stays in music or in talk
kind: adr
status: accepted
updated: 2026-10-16
---

# 64. Auto-play stays in music or in talk

- Status: Accepted
- Date: 2026-10-16

## Context

The queue mixes music (`MediaContentKind.MUSIC`) and talk, and since ADR 42 one tap moves between them. Auto-play still
took whatever came next, so a podcast could end into a song. Dewi: "If I'm in non-music mode … I don't want to listen to
music." He chose both directions, Explore (if on) when the kind runs out, else stop, and no setting.

## Decision

- When an item ends, the queue plays the next item **of the same kind** (`PlaybackQueue.continueInKind`, through
  `QueueStepping.step(direction, keepTo)`); items of the other kind are passed over and stay queued, counted in the log.
- Gapless arming asks the same question (`peekNext` stays in kind), so the armed successor is the one auto-play would
  choose.
- When only the other kind is left ahead (`onlyOtherKindAhead`), `AutoAdvancer` hands over to Explore when it is on and
  otherwise stops, logging why, instead of falling back to related videos.
- With repeat queue on, the queue first starts again from the top **in the same kind** (`playFromTheTop`), and only
  stops for "only the other kind is left" when nothing of its kind will play. Repeat was not part of Dewi's choices;
  this is the implementer's reading of the two rules together, found by the batch review (2026-10-16), and his to
  confirm.
- Manual ⏭ is unchanged: it plays the next item whatever its kind.

## Consequences

- Tests: `AutoPlayStaysInMusicOrTalkTest` (4), two new `AutoAdvancerTest` cases.
- "Stopped because only music is left" is a new way for the queue to stop; the log line names it, so a report can tell it
  from a failure.
