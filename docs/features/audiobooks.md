---
title: Audiobooks
kind: feature
status: shipped
area: search
updated: 2026-10-31
---

# Audiobooks

**Ask (2026-10-31):** "a way to search audiobooks … torrent audiobook sites … similar to the way we do
for torrents". Decisions and reasons: [ADR 105](../adr/0105-audiobooks-are-books-from-the-archive-and-torrents.md).

## What it does

- Search shows **🎧 Audiobooks** (free: LibriVox and the Internet Archive) and, with a home server,
  **🎧 Audiobook torrents** (AudioBook Bay and The Pirate Bay's Audio Books category).
- ▶️ on a free book plays it from where you were: every chapter is queued as one group, starting at
  the chapter in progress or the next one you have not finished. Tapping the row opens the book.
- The book's page lists its chapters in order with **▶️ Play the book / Resume at chapter N of M** and
  **⬇️ Download the book** (choose chapters; downloaded ones are already unticked). Tapping a chapter
  plays the book from that chapter. There is no subscribe button.
- An audiobook torrent plays like any torrent (prepared on the home server, through the VPN); its audio
  files queue in chapter order and an `.m4b` book plays as one item.
- Audiobooks appear only in the 🎧 sections, never among the film torrents. AudioBook Bay results say its seeders are not reported. Book chapters carry no follow mark.
- Book chapters keep your speed, never skip silence (a toggle while one plays is for that chapter
  only), and are never cut short by a quiet ending. Rows wear 🎧 BOOK.

## Seam

`ArchiveBookSearchSource` and `TorrentSearchSource(category = AUDIOBOOKS)` behind `SearchSource`; a
book is a `MediaSource.PodcastFeed` at `archive.org/metadata/<id>`, read by `ArchiveItemParser` in
`DefaultPodcastRepository`; `BookPlayer` (app) starts a book; `MediaContentKind.AUDIOBOOK` tunes it.

## Logs

`[audiobook]` lines: each Internet Archive search (books found, how many LibriVox), a book page opened,
which chapter a book starts at and why ("where you were" or "chosen", finished and started counts), a
book that would not load. `[torrent]` says which indexers an audiobook search asked and how many carry
no audiobook category; `apibay … &cat=102`. `[playback]` says when a chapter plays with skip-silence off.
