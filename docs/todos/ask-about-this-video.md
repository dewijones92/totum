---
title: Ask about this video
kind: todo
status: built 2026-11-07, Batch A (tests owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Ask about this video

Dewi 2026-11-05 (agreed idea): "what did they say about X?" answered from the transcript, each answer with a tap to jump to that moment. Shares the transcript plumbing with TL;DR; dry runs.

Whole-batch review: built with TL;DR as one transcript digest; reuse `TranscriptTab.matchesOf` and its seek callback.

## Built 2026-11-07

Built with TL;DR and chapters as one transcript digest ([feature](../features/transcript-digest.md)): an "✨ Ask about this"
box in the Transcript tab; the answer comes with up to three "▶️ 12:05 · “quote”" lines that seek, each confirmed in code
against the transcript (60% of the quote's words near that time); a long transcript is sent as the parts that share the
question's words. `TranscriptTab.matchesOf` was not reused (a tidied quote is rarely a substring): the seek callback is.
Dry-run on ten questions including three the video does not answer and one injection.

Decisions, dry runs and limits: [ADR 142](../adr/0142-tl-dr-chapters-and-ask-about-this-video-are-one-transcript-digest.md); the feature doc: [transcript-digest.md](../features/transcript-digest.md).

## Tests owed

- `DigestRulesTest` covers the moment checks (snapping, a quote not in the transcript, nothing for "not found");
  owed: the Compose answer rows (a moment seeks, "not found" shows with no moments, "no moment confirmed" note, ❌), the
  Ask box's send button and keyboard action, several questions stacking newest first.
- `DigestServiceTest` covers a long transcript choosing blocks; owed: the same for a question with no matching words.
- A device check: ask something of a video and tap its moment.
