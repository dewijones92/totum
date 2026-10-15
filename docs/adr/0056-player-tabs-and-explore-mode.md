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
- **Explore** (`playback/ExploreMode.kt`): when on, an item that ends is followed by the first related video not yet
  played in this run, played as a *peek* so the queue is not touched. It remembers the queue item it left
  (`resumeAfter`, kept in `ExploreResumeStore` across restarts); switched off, the next advance or **Next** plays the
  item after it. Next while exploring plays another related video. While Explore is on, gapless does not arm the
  queue's next item, and turning it on drops one already armed, so the end always reaches Explore.
- Explore never picks a live stream (it never ends, so the queue would wait for ever), a Short or a members-only
  video. While exploring, the player's Up next lists what follows the queue's place, and its rows act on the item
  they show (by identity, not position).
- Podcasts have no related list; with Explore on, a podcast's end lets the queue carry on.
- Toggle: `ExploreToggle` in the Queue header, the mini player and an Explore line at the top of the Up next and
  Related tabs; Settings → Start in Explore mode (default off). Every switch and decision is logged under `explore`.

## Consequences

- One extra related request per YouTube item bound while the app is open (through the polite gate).
- Tests: `ExploreModeTest`, `AutoAdvancerTest`, `GaplessArmerTest`, `WatchRatingTest`; verified on totum-api35:
  explore → related → off → Next resumed the queue at the item after the one it left.
