---
title: TL;DR and chapters for long items
kind: todo
status: built 2026-11-07, Batch A (tests owed)
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

## Tests owed

- `DigestRulesTest` and `DigestServiceTest` exist; owed: `FileDigestStore` (round trip, corrupt file),
  `TranscriptText.forQuestion` (best-scoring blocks and neighbours first, glimpses for the rest, within budget),
  `TranscriptText.fit`, a TL;DR line cut at a sentence end.
- Compose: the panel offers the button only from ten minutes, shows the card with the playing chapter bold, a tap on a
  chapter seeks, a failure shows ❌ inline and in the strip, "Summarise again" calls again.
- A podcast with a feed transcript through the same panel, and a device check on a long video.
