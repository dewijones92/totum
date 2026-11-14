---
title: A radio seeded from several songs, not one
kind: todo
status: built 2026-10-15 (ADR 27 revision 2026-10-15; unit-tested)
area: music
priority: high
requested: 2026-10-14
updated: 2026-11-14
---

# A radio seeded from several songs

> **Status correction (2026-11-14):** this file said "requested, to refine with Dewi" until today, but it was built on
> 2026-10-15 (`52b9fe54`) and the index row was corrected on 2026-10-22 while this file was not. The multi-select bar's
> "📻 Radio from these" (`radio_from_these`, `strings.xml`; `BulkAction` in `Selection.kt`, offered for two or more items
> with a YouTube id) plays each seed's radio in turns as one radio, a failed seed left out ([ADR 27](../adr/0027-radio-repeat-and-shuffle-are-queue-features.md),
> revision 2026-10-15; `MusicRadioTest`: "a mix plays the seeds' radios in turns…", "a mix where no seed gives a radio queues
> nothing"). YouTube Music has no several-seed radio, so it interleaves one-song radios. Not checked on a phone from this
> doc. The "To refine" list below is the original question list, answered by the ADR.

Dewi, 2026-10-14: *"I want a YouTube mix radio thing … based on two songs or three songs, or like 10 songs or
something. So add this, and we'll refine it some other time."*

Today a radio starts from ONE song (or an artist's station) and tops itself up from YouTube Music's continuation
(ADR 27). The ask is a radio whose seed is a handful of songs, so the mix sits between them.

## To refine

- Where the songs are picked: multi-select in a list, a "Start a radio from these" on a selection, the queue?
- Whether YouTube Music offers a multi-seed station (a `watchPlaylist` with several `videoIds`, or the
  "Radio from these" the web app shows for a selection), or whether we interleave several one-song radios.
- How many seeds before it stops helping; whether the seeds play first.
