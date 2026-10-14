---
title: Radio tops itself up; repeat and shuffle are queue features, for both pillars
kind: adr
status: accepted
updated: 2026-10-09
---

# 27. Radio tops itself up; repeat and shuffle are queue features, for both pillars

- Status: Accepted
- Date: 2026-10-09

## Context

Dewi chose an **endless** radio that tops itself up, and while the Music tab was being built asked for
*"repeat songs, shuffle album, shuffle artist etc ... usual stuff like that"*. Album and artist shuffle
belong to their pages; repeat and shuffling what is queued are not music-specific, and the twin laws
(unified, DRY) say they must not be built twice.

## Decision

- **Radio** (`MusicRadio`): started from a song (long-press "Start radio"), an album or an artist; the
  first batch is inserted after the current item as a "Radio · <seed>" group. When three or fewer of its
  songs are left ahead of the playhead it fetches the continuation and appends to the group
  (`PlaybackQueue.appendToGroup`); songs it already offered are not offered again; up to three empty
  batches are tried; it stops topping up once something outside the group plays. Every decision is logged
  under `[radio]`. Not persisted: after the process dies the queued songs remain and the top-up stops.
- **Repeat** (`RepeatMode` OFF / QUEUE / ONE, a setting, a Repeat tile in the player) is decided in
  `AutoAdvancer`, the one place that decides what happens when an item ends, so it works with the screen
  off. ONE replays the ended item even with auto-play next off, and gapless stands aside; the next button
  still skips. QUEUE starts again from the top (`playFromTheTop`) when nothing after is playable, before
  the related-video fallback.
- **Shuffle up next**: a Queue-header button shuffles everything after the current item; what is playing
  and what came before stay put, and items keep their group.

## Consequences

- A podcast queue can repeat and shuffle exactly as music does.
- Repeat QUEUE wraps without gapless: the first item is not armed from the last.

## Amended 2026-10-14: the radio is endless, and says so

Dewi, 2026-10-14: *"make the radio, the music radio, continuous … label it … really obvious"*; report
`20261014T193916`, *"the radio doesn't seem to be infinite"*. The station was held only in memory, so an app
restart (every update) ended it; playing anything outside it stopped it for good; and when YouTube offered no
continuation it simply ran out. Now `MusicRadio` saves its station (`files/radio.txt`) and restores it after a
restart while its songs are queued; playing something else pauses the top-ups until the radio is playing again;
with no continuation left it carries on from its last song (`RadioSeed.forSong`). A radio group's header says
**♾️ Endless radio**, and each radio song is played with its artist line as **♾️ Endless radio · artist**
(`labelledForPlay`, at the queue's hand-off), so the mini player, the player, the notification and the lock
screen all show it. Tests: `MusicRadioTest`, `EndlessLabelTest`, `GroupHeaderShufflePlayTest`.

