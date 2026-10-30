---
title: Torrent results carry trust badges
kind: adr
status: accepted
updated: 2026-10-30
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
  (`OperationEnd.Done.note`).

## Consequences

- One more request per search, from the phone, to apibay (about 0.4 s); a second copy of every TPB
  result arrives and merges.
- A result from an indexer that gives nothing (1337x, Torrentio's other sites) says `❔ Uploader
  unknown` rather than pretending; a 1337x result without a magnet cannot be matched until chosen.
- The red flags and the group list are heuristics: a fake can carry a good group's name, so the
  uploader chip is the strong signal and the group chip the weak one.
- Tests: `TorrentTrustTest` (9), `ApibayTest` (4), the merge in `HttpHomeTorrentServerTest`, the
  sort-down in `TorrentSearchSourceTest` (2), `TorrentTrustChipsTest` on the device.
