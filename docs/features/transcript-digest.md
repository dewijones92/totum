---
title: TL;DR, chapters and ask about this video
kind: feature
status: built 2026-11-07, Batch A (tests owed; not yet seen on a device)
area: ai/player
updated: 2026-11-07
---

# ✨ TL;DR, chapters and ask about this video

In the player's Transcript tab: a three-line summary with tap-to-jump chapters, and a box that answers "what did they
say about X?" from the transcript with moments to jump to. Decisions, the dry runs and the limits:
[ADR 142](../adr/0142-tl-dr-chapters-and-ask-about-this-video-are-one-transcript-digest.md); the briefs and the tests
still owed: [tl;dr and chapters](../todos/tldr-and-chapters.md), [ask about this video](../todos/ask-about-this-video.md).

## What it does

1. Open a video or podcast that has a transcript, then the **Transcript** tab. Items of ten minutes or more offer
   **✨ Summarise this**; the progress strip shows it working (a few seconds to half a minute).
2. The card shows three lines, a chapter list (tap one to jump; the one playing is bold) and a note that AI wrote it.
   It is kept on the phone, so it is there next time without a call; **🔄 Summarise again** makes a new one.
3. **✨ Ask about this** takes a question. The answer is one to three sentences, then up to three
   "▶️ 12:05 · “quote”" lines; tapping one seeks there. When the transcript does not cover it the answer says so and offers
   nothing to jump to; an answer whose moments could not be confirmed in the transcript says that too.

## Seam

- `core/data/digest/`: `TranscriptText` (cues to timed blocks, the text sent, thinning, picking the blocks that fit a
  question), `DigestRules` (the checks applied after the call), `DigestService` (the phone's client, the on-disk cache
  `digests.json`), the request and answer types.
- Pi: `cli/.../serve/tasks/TranscriptPrompts.kt`; routes `POST /ai/transcript-digest` and `/ai/transcript-ask`, caller
  `transcript`, no tools. `TranscriptRequestTool` is the dry-run helper that builds a request from a caption file.
- App: `ui/player/TranscriptDigestPanel.kt`, shown by `TranscriptSection`; `DigestHost` is provided with the other row
  capabilities.
- Reused: `HttpAiJobs` and the AI job routes ([ADR 141](../adr/0141-a-spoiler-shield-hides-results-in-titles-pictures-and-lengths.md)),
  `TranscriptLoader` and its cues, the transcript tab's seek callback, the operations strip.

## Limits, honestly

A transcript over about 100,000 characters (roughly 2 hours of speech) is thinned for a summary and chosen by keyword
for a question, because `claude -p` takes its prompt as one argument of at most about 128 KB. A question's moments
can sit up to 20 seconds from the start of the sentence when it crossed a block. Only YouTube captions were dry-run.

## Diagnostics

`digest` lines: what was summarised, the cues, blocks and characters sent, a kept summary reused (no call), a question and
whether the parts were chosen for it, every chapter or moment tapped. On the Pi: what the model gave and what the
checks kept.
