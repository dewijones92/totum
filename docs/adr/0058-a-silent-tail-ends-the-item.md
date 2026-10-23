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
  (`endsWithNoSpeechLeft`, `TailSpeechScans`). It ends only after **playing** at least 5 s past the last speech
  (`RUN_NOT_SEEK_MS`): a seek or a scrub that lands past it does not end the item, so you can look around the
  ending. A scan that hears no speech at all says nothing (null), rather than "speech ends at 0" ending the item at
  once; the decoder reads 16-bit and float PCM, gives up after 120 s, and stops safely (review of 2026-10-23). Same switch as the silent tail, so never for music or live.
- **Streams too** (2026-10-23, Dewi's choice: audio-only streams, Wi-Fi only): an **audio-only** stream (no video
  track at all in the player, so a video with its picture switched off does not count, since reading its tail
  would fetch the video) on an **unmetered** network is scanned the same way, through MediaExtractor's own HTTP
  reads, once it has played 10 s (past YouTube's fresh-URL 403 window, and off the start's bandwidth). The rule is
  `tailSourceOf` (file / stream / not yet / not at all, with the reason logged). Measured on totum-api35, a 20-minute
  YouTube audio stream: 180 s of tail scanned in 18.4 s while the app received 3.7 MB in all (player included),
  against ~19 MB for the whole audio. A video stream, mobile data, or anything not over http is left to the silent
  tail. Logged:
  the scanned span, how long it took, where speech ends and how much it would skip, and the ending itself.
- The app tells the service per item, as it does skip-silence (`ACTION_SILENT_TAIL`): on when Settings → End on a
  silent tail is on (default) and the item is talk (`ItemTuning.silentTailEnds`: not music, not live).
- The meter sits after the silence cutter: with skip-silence on, a silent tail is already cut to almost nothing,
  so the watch rarely needs to act.
- **End cards**: `SkipCategory.OUTRO` joins `DEFAULT_CATEGORIES`, and a phone that had already chosen its categories
  gets it added once (`withEndcardsOnce`, remembered by `endcards_default_applied`), so switching it off again sticks.

## Consequences

- Review of 2026-10-23: a playlist or manifest address (`.m3u8`, `.mpd`, a torrent's audio-only HLS) is not scanned,
  since MediaExtractor cannot read one; "metered" is one rule, `activeNetworkIsMetered` in `:core:playback`, used by
  the app's `NetworkStatus` and the service alike. Not done: a scan is not cancelled when the item changes or the
  network turns metered mid-scan, and a blocking read can hold the single scan thread past its 120 s limit.

- A video whose ending is quiet but meaningful (a whispered last line, a long pause before a punchline) can lose
  up to its last minute. The threshold is five seconds of near-silence to make that rare.
- Credits with music over them are not caught here; SponsorBlock's end cards and the per-show outro (ADR 57) are.
- Logs: `<id> is in its last Nms: listening for a silent tail`, `<id> went silent for Nms at …: ending it`, and the
  per-item switch; reports carry `settings.silentTailEnds`.
- The watch acts once per item, and again only if the item leaves the tail and comes back (a seek back).
- Tests: `TailSourceTest` (JVM, red first: which source is scanned, and when), `TheTailIsScannedForSpeechTest` (instrumented: the same audio over HTTP from a local range server ends at the same last words as the file; a 240 KB fixture is fetched whole, so "reads only the tail" rests on the emulator measurement above), `SilentTailTest`, `MusicPlaysPlainTest`, `OneTimeDefaultsTest`, `PreferenceBackupTest`. Not yet seen on a
  device: a real silent tail ending an item (the seek to the end is the same one an outro skip uses).
