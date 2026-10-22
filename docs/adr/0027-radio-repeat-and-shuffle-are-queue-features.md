---
title: Radio tops itself up; repeat and shuffle are queue features, for both pillars
kind: adr
status: accepted
updated: 2026-10-15
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
**♾️ Endless radio**, and each radio song is handed to the player marked `playedAs = ♾️ Endless radio` (`labelledForPlay`, at the
queue's hand-off); only the session's artist line shows it (*♾️ Endless radio · artist*), so the mini player, the
player, the notification and the lock screen say it while history, downloads and stats keep the real artist.
The first version wrote the mark into the item's author, which a stream's resolved author then replaced and
history then saved (found by the review); the station starts with the app, so a restart cannot leave a radio
labelled endless with nothing topping it up. Tests: `MusicRadioTest`, `EndlessLabelTest`, `GroupHeaderShufflePlayTest`.

## Revision 2026-10-15: radio from several songs

Dewi chose "radio from several selected songs" from the product review. YouTube Music offers no several-seed radio
that this app knows of, so the multi-select bar's "📻 Radio from these" (two or more items with a YouTube id) fetches
each song's radio, takes them in turns with no song twice (`interleaveRadios`: each seed leads, then each radio's
second song, …), and plays the blend as one radio group (`radio:mix:<first seed>`, "Radio · A, B + 2 more") that tops
itself up from the first seed's continuation. A seed whose radio fails is logged and left out. `RadioMixTest`.

### Amendment (2026-10-22): reshuffled repeats, and shuffling several things together

Dewi: *"shuffle and repeat current playlist or current group of playlists … a bit more options"*. Repeat gains two modes
beside Queue and This group: **Queue, reshuffled each round** (when the queue runs out, the order of its groups and the
items in each are shuffled, groups kept whole, then it plays from the top) and **This group, reshuffled each round**
(the group's run is reshuffled before it starts again). Neither round starts on the item that just ended.
`RepeatMode.repeatsQueue` / `repeatsGroup` / `reshuffles` keep every rule in one place (the advancer, gapless arming);
`reshufflingRun` / `reshufflingEverything`, `ReshuffleRoundTest` (seen red against no-op stubs).

Two ways to mix several sources: the queue's selection bar has **🔀 Shuffle together** (ticked groups or rows become
one shuffled group, "🔀 ArtistA + ArtistB"), and the Subscriptions list's selection bar has **🔀 Shuffle all of these**
(every page of every ticked channel or show, one `ShuffleAll` target, `shuffleTargetOf`). With This group, reshuffled,
a mixed group becomes "a group of playlists on shuffle and repeat".

Review corrections (2026-10-22): "Queue, reshuffled each round" reshuffles only when nothing in kind is left to play
(an advance can also fail because items were refused offline, and that must not scramble the order), and a round's first
playable item in kind is never the one that just ended, even behind an item of the other kind (`ReshuffleRoundTest`,
`SkipFinishedTest`, seen red).
