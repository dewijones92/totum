---
title: End talk early when the rest has no speech
kind: todo
status: built 2026-10-23 for downloaded files; streams not yet
area: playback
priority: high
requested: 2026-10-22
updated: 2026-10-23
---

# End talk early when the rest has no speech

Talk only (never music), on by default with a setting (Dewi's choice): a downloaded file is scanned ahead with the speech model, and when nothing after this point is speech (end cards, outro music, silence) the item ends; a stream is judged on what is buffered. Extends ADR 58's silent tail (RMS today, no speech model).

Built 2026-10-23 for files on the phone ([ADR 58](../adr/0058-a-silent-tail-ends-the-item.md)): the tail is decoded and
scanned with the speech model (18 s of test audio in ~1 s on the emulator; a 3-minute tail of a 15-minute download in
26 s at low priority), and the item ends 2.5 s after the last speech. Tests: `NoSpeechLeftTest`,
`TheTailIsScannedForSpeechTest` (speech then music ends where the scan begins; speech into the tail ends at 50.1 s of
a true 50.0 s). **Not yet:** streams (YouTube, torrents, podcast enclosures) — judging "what is buffered" needs a second
decoder over the playback cache, or fetching the tail early; until then the silent tail covers them.
