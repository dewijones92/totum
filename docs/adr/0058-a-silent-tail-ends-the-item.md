---
title: A silent tail ends the item, and end cards are skipped by default
kind: adr
status: accepted
updated: 2026-10-23
---

# 58. A silent tail ends the item, and end cards are skipped by default

- Status: Accepted
- Date: 2026-10-15

## Context

Dewi, 2026-10-15: *"sometimes at the end of videos there are like dead time … silence where it's just … showing
credits … should we just skip to the next item"*. Asked, he said the dead time is actually silent (not music over
credits), that it should move on to the next item, on by default for videos and podcasts and never for music, and
that SponsorBlock's Endcards/Credits category should be on by default too.

## Decision

- **Silent tail** (`core/playback/…/SilentTail.kt`): the volume booster, which already reads every 16-bit sample,
  feeds a `SilentRun` meter (10 ms blocks, silent below RMS 60 of 32767, about -55 dBFS; any louder block starts
  the count again). In the playback service, `SilentTailWatch` checks every 500 ms: in an item's last minute, or
  its last tenth if that is shorter (`tailWindowMs`), five seconds of silence (`SILENT_FOR_MS`) seeks to the end,
  so the item ends exactly as if it had finished (Ended, or a gapless crossover).
- **No speech left** (2026-10-23, Dewi's batch item 5): for a talk item played from a **downloaded file**, the
  service decodes its last three minutes (or last 30% if shorter) on a low-priority thread (`scanTailForSpeech`,
  MediaExtractor + MediaCodec) and runs the bundled speech model over it (`SpeechTrack.speechEndsAtFrame`); once
  playback is 2.5 s past the last speech, with more than 5 s left, the item ends the same way
  (`endsWithNoSpeechLeft`, `TailSpeechScans`). Same switch as the silent tail, so never for music or live. A
  stream is not scanned yet (reading ahead would fetch its bytes early); the silent tail still covers it. Logged:
  the scanned span, how long it took, where speech ends and how much it would skip, and the ending itself.
- The app tells the service per item, as it does skip-silence (`ACTION_SILENT_TAIL`): on when Settings → End on a
  silent tail is on (default) and the item is talk (`ItemTuning.silentTailEnds`: not music, not live).
- The meter sits after the silence cutter: with skip-silence on, a silent tail is already cut to almost nothing,
  so the watch rarely needs to act.
- **End cards**: `SkipCategory.OUTRO` joins `DEFAULT_CATEGORIES`, and a phone that had already chosen its categories
  gets it added once (`withEndcardsOnce`, remembered by `endcards_default_applied`), so switching it off again sticks.

## Consequences

- A video whose ending is quiet but meaningful (a whispered last line, a long pause before a punchline) can lose
  up to its last minute. The threshold is five seconds of near-silence to make that rare.
- Credits with music over them are not caught here; SponsorBlock's end cards and the per-show outro (ADR 57) are.
- Logs: `<id> is in its last Nms: listening for a silent tail`, `<id> went silent for Nms at …: ending it`, and the
  per-item switch; reports carry `settings.silentTailEnds`.
- The watch acts once per item, and again only if the item leaves the tail and comes back (a seek back).
- Tests: `SilentTailTest`, `MusicPlaysPlainTest`, `OneTimeDefaultsTest`, `PreferenceBackupTest`. Not yet seen on a
  device: a real silent tail ending an item (the seek to the end is the same one an outro skip uses).
