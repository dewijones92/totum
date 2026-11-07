---
title: TL;DR and chapters for long items
kind: todo
status: built 2026-11-07, Batch A (tests written 2026-11-07; device checks owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# TL;DR and chapters for long items

Dewi 2026-11-05 (agreed idea): a 3-line summary and tap-to-jump sections for long items, built by Haiku from the transcript (Transcript tab, ADR 60; YouTube captions or podcast:transcript). Cached per item; dry runs.

Whole-batch review: built as one transcript digest with ask about this video, cached per item on disk; the phone sends the cues (the Pi has no transcript code).

## Built 2026-11-07

Built with ask about this video as one transcript digest ([feature](../features/transcript-digest.md)): the Transcript tab's
"✨ Summarise this" (items of ten minutes or more), a three-line card with chapters that seek, kept per item on disk
(`digests.json`), a Pi task `transcript-digest` (no tools) whose answer is checked in code. Dry-run on three real
transcripts of 19 minutes, 1 hour and 3.6 hours.

Decisions, dry runs and limits: [ADR 142](../adr/0142-tl-dr-chapters-and-ask-about-this-video-are-one-transcript-digest.md); the feature doc: [transcript-digest.md](../features/transcript-digest.md).

## Tests

Exist (JVM): `DigestRulesTest`, `DigestServiceTest`, and `TranscriptTextTest` (a transcript that fits is sent whole; a long
one keeps the blocks sharing the question's words, with their neighbours, and only glimpses of the rest; the budget holds;
words that say nothing do not pull a block in; `fit` cuts every line evenly and keeps every stamp; empty cues and a long cue;
a summary line over the limit is cut at a sentence end, or at a space when it has none; `FileDigestStore` round trip, a
corrupt file, a missing one, an unwritable path). Seen failing with the neighbour count set to zero.

Still owed, with the reason:

- Compose: the panel offers the button only from ten minutes, shows the card with the playing chapter bold, a tap on a
  chapter seeks, a failure shows ❌ inline and in the strip, "Summarise again" calls again. Needs a device.
- A podcast with a feed transcript through the same panel, and a device check on a long video.
