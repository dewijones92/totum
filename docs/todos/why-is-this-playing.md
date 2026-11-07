---
title: Why is this playing?
kind: todo
status: built 2026-11-06 (tests written 2026-11-07)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Why is this playing?

Dewi 2026-11-06. Tap the playing item: "next in the Peep Show group", "Explore picked it", "repeat one". No reason is
stored today (only `byYou` and log strings); a reason travels with the play path (`PlaybackQueue.play/playAt`) into
`PlaybackState`.

## Built (2026-11-06)

See [why-is-this-playing](../features/why-is-this-playing.md) and [ADR 131](../adr/0131-a-play-carries-its-reason-and-the-notification-says-whats-next.md).
One `PlayReason` set by the queue where a play starts, carried on `PlaybackState.playReason`; the title tap in the
full player and a long-press menu on the mini player show it.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`PlayReasonsTest` (the reason set by `playNow`, `playAll`, `peek`, `jumpTo`, `playNextInQueue`, `playPreviousInQueue`,
the New group and radio groups, and shown only for the current item), `PlayReasonPhraseTest` (every reason and way maps
to its own string, with and without a name, and the report's `describe`), instrumented `BatchAWordsTest` (the sentence
for each reason) and `BatchAScreensTest` (the dialog's text, the unknown case, the mini player's menu asking). Seen
failing with the previous-reason mapping changed.

Still owed: the reason set by the pinned tile menu's Shuffle and song-seed actions, by each shuffle and by the
notification paths (each is a call site, not a queue entry point); the dialog kept across a recovery replay (needs the
Media3 controller); an emulator look at the title tap and long-press menu; whether the title tap clashes with the
player's other gestures.
