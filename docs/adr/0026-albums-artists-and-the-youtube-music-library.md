---
title: Albums, artists and your library come from YouTube Music; Play inserts after what is playing
kind: adr
status: accepted
updated: 2026-10-09
---

# 26. Albums, artists and your library come from YouTube Music; Play inserts after what is playing

- Status: Accepted
- Date: 2026-10-09

## Context

Probed live on 2026-10-09 before building:

- The anonymous `WEB_REMIX` client answers album and artist search (pinned filters beside the songs one),
  album pages (`MPREb_…` browse ids, which also name the album's `OLAK5uy_…` playlist), artist pages
  (top songs, albums, singles, similar artists, Mix and Shuffle seeds) and radio (`next` with
  `RDAMVM<id>` / `RDAMPL<playlist>` seeds and a continuation).
- `WEB_REMIX` with a bearer token answers **HTTP 400**, as `InnerTubeClient` already recorded. But the
  TV client, signed in, answers the YouTube Music library tabs: `FEmusic_last_played`,
  `FEmusic_liked_playlists` (with Liked Music as `VLLM`), `FEmusic_liked_albums` and
  `FEmusic_library_corpus_artists`, as the same TV tiles the app already parses.
- Library artist tiles carry **no channel id**, only a radio seed.

Asked what Play on an album should do to the queue, Dewi chose **insert after current**.

## Decision

- `:lib:innertube/music`: `YouTubeMusicCatalogue` (anonymous) and `YouTubeMusicLibrary` (TV client,
  signed in), parsed by shape against real trimmed fixtures; the library fixtures have the account's
  titles swapped for public catalogue ones, because the repository is public.
- Play and Shuffle use the queue's `playAll` with a `QueueGroup` titled after the album, which inserts
  after the current item and starts; Add to end is a grouped `enqueueAll`; Download fetches every track
  as audio, and the page counts how many are on the phone (`offlineCount`).
- A library artist's page is found by an exact-name artist search; if none matches, its mix plays.
- A shared link naming an album playlist opens the album page; a song shared from inside an album also
  plays, as before.
- The Music tab's home is the YouTube Music library (recent, albums, playlists, artists) and songs played
  here (play history, kind MUSIC). No recommendation feed, matching the Videos tab's dopamine fast
  ([ADR 19](0019-videos-hidden-until-asked.md)).

## Consequences

- The renderers change without notice; `LiveMusicCatalogueTest` (`RUN_LIVE_MUSIC=1`) is the canary and
  cannot run in CI.
- Premium-only tracks will not play, as before.
