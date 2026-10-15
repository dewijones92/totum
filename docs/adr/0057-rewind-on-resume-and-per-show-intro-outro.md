---
title: Rewind on resume, and skipping each show's intro and outro
kind: adr
status: accepted
updated: 2026-10-15
---

# 57. Rewind on resume, and skipping each show's intro and outro

- Status: Accepted
- Date: 2026-10-15

## Context

From the product review of 2026-10-15, Dewi chose (among others): rewind a little on resume, scaled by how long it
was paused, never for music, a Settings switch on by default; and skipping the intro and outro of a show or channel
he sets once.

## Decision

- **Rewind on resume** (`core/playback/…/ResumeRewind.kt`): one rule, `rewindAfterPauseMs`: nothing under 3 s
  paused, then 2 s growing in a straight line to 20 s at an hour, 20 s beyond. Applied in two places that share it:
  - within a session, `ResumeRewindListener` records a pause (not one caused by the end of an item) and, on resume of
    the same item at the same place (moved under 1.5 s), seeks back;
  - after the app was closed, `play()` resuming a saved position rewinds by the time since that position was saved
    (`PlaybackProgressStore.lastSavedAtEpochMs`, the row's `updatedAtEpochMs`). A random shuffle start has no saved
    time, so it is not rewound. An explicit start (a re-fetched stream) is not rewound either.
  - Never for music (`ItemTuning.keepsChanges`), a live stream, or with Settings → Rewind a little after a pause off.
    A same-item re-fetch keeps the pause record; another item drops it.
  - A phone call, sat-nav prompt or alarm counts as a pause: Media3 keeps `playWhenReady` on through a transient
    audio-focus loss and sets a suppression reason, so the listener watches that too. Exsurge's break and the sleep
    timer are ordinary pauses, so they rewind as well. The player stopping at an item's end is not a pause.
  - The start is chosen once (`startFor`), for a fresh play and for a gapless arm alike, and logged where it is
    used (`<id> starts at …: <why>`, `gapless: <id> would start at …`). A gapless adoption plays on from the arm's
    start, and a fresh start forgets any in-session pause, so a resume is never rewound twice.
- **Per-show intro and outro** (`core/domain/SourceSkips.kt`): a show or channel can have an intro
  end (absolute: skip 0 → it) and an outro length (from the end: every episode loses its last N). Stored in one
  preference map (`AppPreferences.Settings.sourceSkips`, backed up with the settings, listed in reports as
  `settings.sourceSkips`) and turned into skip segments at play time against the item's real duration, by the same
  ticker that skips SponsorBlock, so both pillars get it. An outro skip seeks to the end, so the item ends as usual.
  Set in the player's Details tab: Intro ends here / Outro starts here / Clear (not offered for music or live, and
  not at 0:00). A start that would land inside the intro begins at its end instead, so the player never fetches
  the intro only to seek past it (and a gapless hand-over stays seamless). Only when the item's length is known: an
  item with no length yet (a fresh RSS upload) starts at its saved place and the ticker skips the intro once the
  player knows the real length, so a short item can never start past its own end.
- The show is `MediaItem.showKey()`: a video's YouTube channel id (`yt:UC…`, from its stated channel, the same
  facts the follow mark uses), else its channel link; an episode's feed; else the channel or show name
  (`name:video:…`, `name:podcast:…`). Not the item's `sourceId`: that is often a placeholder for the list it came from (`ytrelated`,
  `search:ad-hoc-video`), which would have applied one video's intro to every related video. Each item logs
  `show <key>, intro/outro <set or not set>` when it starts.

## Consequences

- A resume can land up to 20 s earlier than the saved position; the log line says by how much and why
  (`resumed`/`resuming saved … rewound`, or `no rewind (…)`).
- A saved position touched by an account reconcile has a newer saved time, so it rewinds less.
- Setting an outro while past its start skips to the end at once.
- A channel seen without its id (the TV home feed names channels only) is keyed by name, so a skip set there and
  one set from a video with a channel id are different shows.
- Tests: `ResumeRewindTest` (the start rule and every pause path: a pause, a call, an item's end, a re-fetch, a
  fresh start), `SourceSkipsTest`, `PreferenceBackupTest`, `ExploreModeTest`, `NextEpisodesTest`. Verified on
  totum-api35 (2026-10-15): the Details section draws, and Intro ends here saved "skips the first 2:10" and logged
  it. Not yet seen on a device: an outro reaching the end, and a call's rewind.
