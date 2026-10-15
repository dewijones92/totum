---
title: Player tabs, and Explore mode
kind: adr
status: accepted
updated: 2026-10-15
---

# 56. Player tabs, and Explore mode

- Status: Accepted
- Date: 2026-10-15

## Context

Dewi, 2026-10-15: *"in the video player screen … a section for related videos and … a section for what's playing
next … and maybe have a mode for automatically playing related videos instead of the ones in my queue"*. Up next and
Related existed but sat below the description, lyrics and chapters, and Related only showed with the picture on.
He chose: tabs under the controls; an Explore mode that chains related videos and leaves the queue waiting;
Related everywhere (Listen mode and music too); the toggle in the player, the Queue header, the mini player and a
Settings default.

## Decision

- The full player has a tab strip right under the play controls and Like row: **Up next · Related · Comments ·
  Details** (a podcast: Up next · Details). Up next shows the first 8 queued items and a count; Details holds lyrics,
  notes, chapters and SponsorBlock. The toggles grid sits below the tab content.
- Related loads as soon as a YouTube item (song included) is bound, picture or not; comments wait for their tab or
  the picture (`WatchViewModel.loadComments`).
- **Explore** (`playback/ExploreMode.kt`): when on, an item that ends (or Next) is followed by the first related video
  not yet played in this run, played as a *peek* so the queue is not touched. Never a live stream (it never ends), a
  Short or a members-only video; a related list that arrives after you chose something else does not interrupt it.
- **The queue keeps its place** (`QueueSnapshot.anchor`, revised after review): a peek records the queue item it left,
  and every way of moving on with no cursor (gapless arming and crossover, auto-advance, Next, stall and recovery
  moves) carries on from the item after it; Up next lists what follows it. Choosing a queue item yourself clears it,
  and it is kept across restarts (`QueuePlaceStore`). So switching Explore off, or Explore declining (no related list,
  a failed lookup, the cap), carries on from the place rather than the top. This also changes a plain peek (a
  one-off Watch/Listen from a sheet): the queue now resumes after the item you were on, as the peek's own doc promised.
- While Explore is on, gapless does not arm the queue's next item, and turning it on drops one already armed.
- Previous while exploring restarts the current item; it does not walk back through explored videos.
- Explore acts only when auto-play next is on.
- A podcast's "related" is its own show (revised 2026-10-15, `nextEpisodesOf`): the unfinished episodes published
  after it, oldest first, then the older ones, newest first. A show you do not subscribe to has none, so the queue
  carries on from its place.
- Explore never plays something already in the queue (revised 2026-10-15): the queue reaches it in its own order, so
  the place cannot skip or replay it.
- **A cap** (Settings → Explore at most: no limit, 3, 5, 10): after that many explored in a row the queue gets its
  turn, and exploring starts again after it. Something else playing in between (a tapped queue item) starts the
  count again.
- Toggle: `ExploreToggle` in the Queue header, the mini player and an Explore line at the top of the Up next and
  Related tabs; Settings → Start in Explore mode (default off). Every switch and decision is logged under `explore`.

## Consequences

- One extra related request per YouTube item bound while the app is open (through the polite gate).
- Reports carry `explore.on` and `queue.place`; every Explore decision is logged under `explore`, with the related
  lookup's failure reason.
- Tests: `ExploreModeTest`, `AnExcursionKeepsTheQueuesPlaceTest` (the real `PlaybackQueue`: gapless's pick, Up next,
  Next and a restored place all carry on from the place), `AutoAdvancerTest`, `GaplessArmerTest`, `WatchRatingTest`,
  `PlayerKeepsEveryControlTest` (device). Verified on totum-api35: explore → related → off → Next resumed the queue at
  the item after the one it left.
