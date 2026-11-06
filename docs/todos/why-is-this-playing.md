---
title: Why is this playing?
kind: todo
status: built 2026-11-06 (code first; tests to follow)
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Why is this playing?

Dewi 2026-11-06. Tap the playing item: "next in the Peep Show group", "Explore picked it", "repeat one". No reason is
stored today (only `byYou` and log strings); a reason travels with the play path (`PlaybackQueue.play/playAt`) into
`PlaybackState`.

## Built (2026-11-06)

See [why-is-this-playing](../features/why-is-this-playing.md) and [ADR 131](../adr/0131-a-play-carries-its-reason-and-the-notification-says-whats-next.md).
One `PlayReason` set by the queue where a play starts, carried on `PlaybackState.playReason`; the title tap in the
full player and a long-press menu on the mini player show it.
