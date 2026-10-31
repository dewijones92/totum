---
title: Torrent results carry trust badges
kind: adr
status: accepted
updated: 2026-10-31
---

# 101. Torrent results carry trust badges

- Status: Accepted
- Date: 2026-10-30

## Context

Dewi, 2026-10-30: *"in Pirate Bay there's like green skulls and pink skulls … they denote something
like if the torrent is safe … Can we put something similar in ours? Not just specific to Pirate Bay,
but … general."* TPB's skulls mark the uploader (green VIP, pink trusted). Prowlarr's Cardigann TPB
definition does not pass that on; TPB's own API (`apibay.org`) does, per result, with the info hash.
His answers: all four signals (uploader, red flags, health, known release groups), a words chip,
flagged results marked and sorted down, and the phone asking apibay itself (no Pi change).

## Decision

- `TorrentResult` gains `leechers`, `publishedAt`, `fileCount`, `uploader` (`Uploader`: VIP, TRUSTED,
  OFFICIAL, MEMBER, UNKNOWN) and `uploaderName`; Prowlarr's parse fills the first three.
- `Apibay` is one more source in the search fan-out beside Torrentio (`alsoAsk`), its results labelled
  "The Pirate Bay"; a failure there is a failed source like any other, never a failed search.
- The one-per-torrent merge keeps the best-seeded copy as before and takes the strongest uploader
  standing and any missing facts from the other copies, so a Prowlarr TPB result gets its badge.
- `TorrentTrust.of` (pure, `:core:data`) turns a result into chips: warnings first, then uploader
  (YTS counts as official), release group, health. `TorrentSearchSource` lists flagged results after
  the rest **before** the cap, and logs the standings and what it sorted down.
- After preparing, `TorrentTrust.filesWarning` names any program among the files in the result toast
  (`OperationEnd.Done.note`), **first**, since Android 12+ shows a text toast's first two lines only.
- **Flags, quality and the release group are read from the release's tail** (`ReleaseName`: the
  words from the first year, episode or quality marker on), never from the title itself: the batch
  review found "The Bat (1959)" flagged as a `.bat`, "Hall Pass" as a password, "Cam (2018)" as a
  cinema copy, "Winter's Bone" as a BONE release and "Charlotte's Web" as a WEB source. The group
  comes only from a `-GROUP` tail or a `[GROUP]` tag. A single episode (`SxxEyy`) has a quarter of
  the size floor, and an explicit `1080p` beats a "4K" mention. Telecine is not a flag (silent-film
  transfers say it).
- The merge keeps a magnet that carries trackers (`&tr=`) over one that does not (apibay's), whichever
  copy has more seeders.

## Consequences

- One more request per search, from the phone, to apibay (about 0.4 s); a second copy of every TPB
  result arrives and merges.
- A result from an indexer that gives nothing (1337x, Torrentio's other sites) says `❔ Uploader
  unknown` rather than pretending; a 1337x result without a magnet cannot be matched until chosen.
- The red flags and the group list are heuristics: a fake can carry a good group's name, so the
  uploader chip is the strong signal and the group chip the weak one.
- Tests: `TorrentTrustTest` (9), `ApibayTest` (4), the merge in `HttpHomeTorrentServerTest`, the
  sort-down in `TorrentSearchSourceTest` (2), `TorrentTrustChipsTest` on the device.

## Amended 2026-10-31: a quality filter and the best copy

Two of the ideas Dewi picked: the torrent section now asks for 40 results (flagged ones still last),
shows 8 at a time with "⬇️ Show N more", and opens with chips — All, 4K, 1080p, 720p, SD (with counts,
from `ReleaseQuality.resolutionOf`) and "💚 Trusted only" (VIP, trusted or official) — under a
"▶️ Play the best copy" button. The best copy (`TorrentPicks.best`) is never flagged and has seeders; it
weighs the picture (1080p first, then 4K and 720p), a trusted uploader, then seeders, within whatever
the chips allow. The choice is logged (`best copy chosen: … (1080p, trusted=true, 210 seeders)`).
Tests: `TorrentPicksTest` (3), device `TorrentControlsTest`.
