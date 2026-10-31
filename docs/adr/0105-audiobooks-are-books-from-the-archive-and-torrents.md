---
title: Audiobooks are Internet Archive items played as podcast feeds, and torrents in the audiobook category
kind: adr
status: accepted
updated: 2026-10-31
---

# 105. Audiobooks are Internet Archive items played as podcast feeds, and torrents in the audiobook category

- Status: Accepted
- Date: 2026-10-31

## Context

Dewi, 2026-10-31: *"I want a way for us to search audiobooks … torrent audiobook sites … services …
similar to the way we do for torrents"*. His answers to the questions asked: **both** sources (free
public-domain books and AudioBook Bay through the Pi), **an own section in search**, and **chapters in
order with resume, skip-silence off**. Later the same morning: *"test this audiobook end to end, like
download an audiobook"* and *"I want the audiobook stuff to be routed via the same thing [the VPN]"*.

LibriVox's own API cannot search: `title=` matches the start of a title only and `author=` a surname
only ("sherlock holmes" and "h.g. wells" find nothing). Every LibriVox recording is also an Internet
Archive item (collection `librivoxaudio`, inside `audio_bookspoetry`), and the Archive's advanced
search does word search over titles and creators. Prowlarr dropped its AudioBook Bay definition; the
site still answers a search and shows each book's info hash without a login, but returns 404 to
Prowlarr's own User-Agent.

## Decision

- **Free books come from one source, the Internet Archive** (`ArchiveBookSearchSource`): every query
  word must be in the title or the creator, within `collection:audio_bookspoetry`, most-downloaded
  first; LibriVox recordings are marked as such. No key, nothing on the Pi.
- **A book is a podcast feed** whose address is `https://archive.org/metadata/<identifier>`;
  `DefaultPodcastRepository` reads that address with `ArchiveItemParser` instead of the RSS parser and
  everything else (preview, page, queue, downloads, progress) is the podcast path. Chapters are one
  speech format (64 kbps MP3 first, else VBR MP3, …), ordered by track number (borrowed from another
  format of the same title when missing), else by the numbers in their names (`NaturalOrder`, shared
  with torrents).
- **A book chapter is `MediaContentKind.AUDIOBOOK`**: it keeps your speed and counts as talk (rewind on
  resume, stays in kind), never skips silence and is never ended by a silent tail; a skip-silence
  toggle while one plays is for that chapter only (`ItemTuning.keepsSkipSilence`).
- **Torrents search by category** (`TorrentCategory.AUDIOBOOKS`): Prowlarr is asked only for the
  indexers whose capabilities carry 3030 Audio/Audiobook, with `categories=3030`; The Pirate Bay's API
  with `cat=102`; Torrentio (films and series only) is left out. AudioBook Bay is a custom Prowlarr
  definition in this repo (`server/prowlarr/definitions/audiobookbay.yml`, a browser User-Agent, two
  pages), and its result resolves to a magnet when chosen exactly as 1337x does (ADR 89). An audio file
  in a torrent plays its own file (no remuxed audio stream) and all-audio torrents queue in natural
  name order; `.m4b` is playable.
- **Books live in their own section only**: a result is an audiobook when Prowlarr lists it under 3030 or The
  Pirate Bay's API under category 102, and the everything torrent search leaves those out (logged with a count).
  AudioBook Bay reports no seeders (its definition fills a placeholder 1), so its results say "❔ Seeders not
  reported by AudioBook Bay" and their subtitle carries no seeder count. A book chapter carries no follow mark
  (ADR 22): a book has nothing to follow. An audio file in a torrent is not warmed as a separate audio stream.
- **Search shows two sections**: 🎧 Audiobooks (free) and 🎧 Audiobook torrents (only with a home
  server). ▶️ on a book plays it; the row opens the book's page.
- **`BookPlayer` is the one way a book starts**: it queues every chapter as one queue group and starts
  at the chapter in progress, else the next unplayed after the last finished (`whereToResume`, moved
  from the torrent code to `:core:domain` and shared with ADR 102), or the chapter tapped.
- **The book page** is the podcast page with no subscribe button (a book gets no new episodes), and
  ▶️ Play the book / Resume at chapter N of M and ⬇️ Download the book (the episode sheet, ADR 80).

## Consequences

- A book is never in Room until something of it is downloaded or queued; its page loads from the
  network each time, so offline it opens only through the queue or Library → Downloads.
- **Routing:** an audiobook torrent streams and downloads through TorrServer, which runs inside
  gluetun's network namespace, so its swarm traffic goes through the VPN like any torrent (checked
  2026-10-31: `NetworkMode` of `torrserver` is gluetun's container). The searches (Prowlarr on the Pi,
  apibay from the phone) and the free books (phone to archive.org) do not go through the VPN, the same
  as for films; Dewi was asked whether he wants them to.
- The AudioBook Bay definition is ours to keep working: the domain moves (`audiobookbay.lu` today).
- Checked end to end on totum-api35 on 2026-10-31: "war of the worlds" found 7 free books (5 LibriVox) and 12
  audiobook torrents; ▶️ queued 27 chapters as one group and started chapter 1 (`kind=AUDIOBOOK`, skip-silence
  off); ▶️ again resumed at 37.6 s; the book page offered Resume at chapter 1 of 27; ⬇️ Download the book fetched
  all 27 chapters (0 failed); offline, ⏭ played chapter 2 from the file; a LibriVox M4B torrent prepared in 2 s
  on TorrServer and played as a book chapter. A 20-year-old LibriVox torrent never gave a file list (stale swarm).
- Tests: `ArchiveBooksTest`, `DefaultPodcastRepositoryTest` (a book previews as AUDIOBOOK chapters),
  `MusicPlaysPlainTest` (book tuning), `ProwlarrSearchTest`, `ApibayTest`, `HttpHomeTorrentServerTest`,
  `TorrentSearchSourceTest`, `TorrentEpisodesTest`, `TorrentPlayablesTest`, `WhereToResumeTest`,
  `BookPlayerTest`, `SearchStreamsPerSectionTest`, and `AudiobookSectionsTest` on the device.
