---
title: Discover, a bolder radio that walks YouTube Music's related-artist graph
kind: adr
status: accepted
updated: 2026-11-13
---

# 167. Discover, a bolder radio that walks YouTube Music's related-artist graph

- Status: Accepted
- Date: 2026-11-13

## Context

Dewi, 2026-11-13: Radio and Mix "play the same sort of thing", so it is hard to discover new music from
them. He asked for another mode, "a bit more varied and daring", as an extra button next to Mix or Radio
wherever those appear, and left the design to the builder: "Don't ask me questions … just be bold … if I don't
like it, I won't use it."

YouTube's radio continuation ([ADR 27](0027-radio-repeat-and-shuffle-are-queue-features.md)) stays close to
its seed by design. The artist page already reads a "Fans might also like" shelf (`MusicArtist.similar`, with
browse ids) and the artist's top songs, so a far-ranging mode needs no new endpoint.

## Decision

- **🧭 Discover** is a second kind of station inside `MusicRadio`, not a second engine. A station gains an
  optional `DiscoverWalk`; the queue observation, the group, the "ahead" trigger, away-from-the-group pause, save
  and restore are Radio's. The group is `radio:discover:<artist id>` titled "🧭 Discover from <seed>", so it is
  endless, shuffles, repeats and takes part in music ⇄ talk like Radio. Its songs play marked "🧭 Discover"
  where a radio's say "♾️ Endless radio", and its header says what it does.
- **Seed**: a song, an album or an artist, resolved to the artist the walk starts from (a song's channel id, else
  its credits; an album's artist id; an artist's own id, else a search by name). The seed's own songs are not
  offered; the first pick is already one hop away.
- **The walk** (`DiscoverWalk`, pure, in `:core:data`):
  - Each step reads the artist pages of up to two candidates and takes one song from each: a random one of the
    artist's top five that is not already offered and not marked not-for-me.
  - Candidates are the seed page's similar artists (one hop) and, from the second productive step, those pages'
    similar artists (two hops), mixed about evenly. Never further than two hops.
  - Skipped: an artist played or liked recently, the artist just picked, an artist offered in the last 40 picks,
    one whose page failed or had nothing to offer. Artists you follow are a fallback, used only when nothing else
    is in range. When the range runs dry the walk re-centres on the artist it last picked rather than stopping;
    only when even that finds nothing is it "dry".
  - "Recently" is read by `DiscoverTasteSource` from: the play history (50), counted music listens in the last 14
    days (stats log), the newest 40 stars, artists in Recently, and Discover pins; "followed" is the account's
    subscribed channels plus the YouTube Music library's artists, if loaded (it never triggers a fetch). Joint
    credits ("A & B") count as each name. Read once and kept five minutes.
- **Politeness**: the artist pages go through the one HTTP gate like every YouTube call. A step asks for at most
  two pages (`PAGES_PER_TOP_UP`), plus the seed's page the first time; no page is asked twice (`ArtistPages` keeps
  them six hours, 200 at most, and the walk never revisits one); top-ups run in the gate's background lane and a
  refill pauses 1.5 s between steps and stops after three. Nothing fans out.
- **Visibility**: `OperationKind.DISCOVER` shows "🧭 Finding music near <seed>" in the progress strip with a
  running count while the first batch is built, then "🧭 … 2 songs to start, more as you listen". When nothing is
  found it ends "📭 Couldn't find anything new near <seed>" and queues nothing.
- **Recently and pins**: `Pin.Discover(artistId, artistName, title)` is recorded when a Discover starts and
  replays through `PinPlayer`; it is pinnable and gets a home-screen icon like every pin.
- **Where the button is**: the song menu (so the player's too), the album page, the artist page beside Mix, the
  tile menus of albums, artists, "all songs" and radio pins (a Discover tile restarts it), and the multi-select
  bar's "🧭 Discover from these" (which starts from the first usable song; a several-artist seed is not built).
- **Logging**: every pick under `[discover]` (chosen artist, hop, which artist it came via, the song, how many of
  its top songs were usable), every skipped candidate with its reason, each step's pages asked and hop limit, the
  taste counts, the seed's resolution, cache hits and misses.

## Consequences

- Discover and Radio share state: starting one replaces the other; the saved station file carries a kind and the
  seed artist (old files, without them, restore as radios).
- It cannot say why an artist is "near" beyond YouTube Music's own similar-artists list; a seed artist with no
  such shelf finds nothing.
- Followed and recent are best-effort sets; a signed-out phone knows only its own history and stars.
- Not built, on purpose: a library of recently-discovered artists beyond the session's memory, ranking by the
  similar list's order, and several-artist seeds.
