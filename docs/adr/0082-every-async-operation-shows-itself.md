---
title: Every async operation shows itself, and an artist's shuffle plays at once
kind: adr
status: accepted
updated: 2026-10-23
---

# 82. Every async operation shows itself, and an artist's shuffle plays at once

- Status: Accepted
- Date: 2026-10-23

## Context

Report 0.1.694 (Dewi: *"that last choppin shuffle all took ages. any way to speed up???"*): shuffling all of Chopin
took 116 s before anything played. The top-songs list came back in 0.8 s; the rest was 201 album lookups one after
another under the polite gate's 2 a second (ADR 51), so going 4 at once would have saved about 10 s. Nothing on
screen said what was happening. Dewi chose to **play now and remember** the list, asked for *"clear ui indicators"*,
and then made it a rule: *"all async ops in this repo should have ui indicators … notification or something"*.

## Decision

- **One seam for "something is running"**: `Operations` (app `operations/`). An operation the user starts is
  `track`ed with a key, a kind and a subject; it reports what it has found, its step of how many, and whether it is
  already playing; it can be cancelled; it always ends with a result (`Done`, `Nothing`, `Failed`, `Cancelled`), and a
  failure is caught and reported, never lost. Logged under `[ops]` with how long it took.
- **The strip**: `OperationsStrip` sits in the shell's banners on every tab, one row per operation (🔀 what, the
  found count, "📀 12 of 201 albums and singles", ▶️ when already playing, a bar that fills when the steps are known,
  Cancel). The result is a toast (`OperationResultToasts`). The per-screen "gathering songs…" and "shuffled N" toasts
  went, and shuffle-all's button reads the seam rather than its own loading map.
- **An artist's shuffle plays at once**: the top songs are shuffled and played as soon as they arrive; the albums are
  walked in the background and shuffled in behind what is playing (`addToGroup`, then `shuffleGroup`, which leaves
  the playing item alone), so the order is uniform except for the first song. If the group has been removed by then,
  nothing is put back. `shuffleAll` returns once music is playing, so a pin or Recently tile does not wait two minutes.
- **The list is kept for a week** (`KeptArtistSongs`, one JSON file per artist under `files/artist-songs/`): a second
  shuffle of the same artist plays from it at once and asks YouTube nothing; after a day it is refreshed quietly in the
  background lane, and anything new is shuffled in.
- Channel and podcast shuffle-all (`ShuffleAll`) reports through the same seam.

## Consequences

- The rule is in CLAUDE.md ("Every async operation shows itself"); artist and album pages loading, preparing a torrent
  and an episode batch are next to move onto the seam (`docs/todos/progress-strip-for-long-operations.md`).
- A result toast only shows while the app is open; the log always has it.
- The first song of an artist shuffle comes from the top 150, not from the whole catalogue.
- Tests: `OperationsTest`, `ShuffleAllPlaysAtOnceTest` (plays before the albums arrive, seen red against the old
  walk-then-play; the kept list asks nothing; a removed group is not refilled), `SeeAllTest`, `WalkDiscographyTest`.

## Amended 2026-10-23: pages, torrents, episode batches, and pressing Play

- Artist and album pages, preparing a torrent, and a batch of episode downloads report through `Operations` too. A row
  shows only once its work has run a second (`shownAt`), so quick loads never flash; a page load has no result toast
  (the page shows it). An episode batch fills "📥 N of M on the phone" until every episode is on the phone or failed, and
  its Cancel stops the ones still downloading.
- Report 0.1.694 (*"no load screen?"*, a White Lotus episode: 7.4 s preparing, 5.7 s to first sound, 16–19 s
  re-buffering after seeks): the full player's Play button read `isPlaying`, which is false while loading, so it showed ▶
  through the whole wait. One rule now, `PlaybackState.isLoading` (wanting to play and buffering, or still starting):
  the button shows ⏸ inside a spinning ring, the video or artwork carries a "⏳ Loading…" card that counts the seconds
  after two, and the mini player uses the same rule, so buffering while paused no longer hides its Play button.

