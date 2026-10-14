---
title: The artist page gets "Shuffle all songs", and its Shuffle station matches YouTube Music's
kind: todo
status: open — agreed with Dewi 2026-10-14; in the 2026-10-14 batch
area: music
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# Artist shuffle

Dewi, 2026-10-14: *"the shuffle button on the artist page … shuffle all songs of the artist or?"*.

## What it did (probed live 2026-10-14, Erik Satie)

- Our **Shuffle** plays YouTube Music's artist Shuffle station (`RDAO…`, from the header's play button) as an
  endless radio. It is **not** every song: YouTube Music's own Shuffle gave 18 of 50 songs credited to Satie;
  ours **drops the button's `params` (`wAEB8gECGAE%3D`)**, and without it the same station gave 9 of 49.
- **Mix** (`RDEM…`) is the artist mix: 6 of 49 by Satie, the rest similar artists.

## Agreed

- **Shuffle all songs**: walk the artist's whole "all songs" listing (`MusicArtist.allSongs`, paged through
  `YouTubeMusicCatalogue.songs`), shuffle it into one queue group, every song once (like ADR 30), recorded
  in Recently.
- **Shuffle** stays YouTube Music's endless station, now sending the button's `params` so it matches the
  YouTube Music app (`RadioSeed` gains `params`).

## Done when

Both buttons on the artist page; a test that every song of a paged listing lands once; the station request
carries `params`; logged pages walked, songs gathered and where it stopped.
