---
title: Shuffle all walks every page of a source into one queue group
kind: adr
status: accepted
updated: 2026-10-22
---

# 30. Shuffle all walks every page of a source into one queue group

- Status: Accepted
- Date: 2026-10-12

## Context

Dewi, 2026-10-12: *"I want the ability to shuffle ALL videos of a channel"*. Asked, he chose: ask about
Shorts each time; do not skip watched items; no cap however large the channel; the result as its own
thing in the queue; and the same on groups. A channel page only ever loaded one InnerTube page at a
time as you scrolled, and nothing could fetch the lot.

## Decision

- One seam for both pillars: `WholeSource` (`:core:data`) walks a list of sources to the end through a
  `SourcePages` port, concurrently per source but at most four requests at once, three attempts per
  page one and two seconds apart, stopping on a repeated continuation or a runaway 2,000 pages, and
  says which parts it could not finish. A walk ending on an empty page is logged as such, not as
  complete, because an unparsed reply looks the same.
- `AppSourcePages` is the only place that knows pillars: a channel pages InnerTube's uploads (and
  Shorts when asked); a channel known only by its handle is resolved to its id once through yt-dlp
  and then paged the same way (yt-dlp alone stops at 20); a podcast gives its stored episodes, or a
  preview of its feed, which is whatever the RSS feed still lists.
- `ShuffleAll` shuffles everything gathered and inserts it after what is playing as one `QueueGroup`
  (`shuffle:<source>`), like album shuffle, so it can be collapsed, skipped or removed in one go, and
  starts playing the first shuffled item at once (`playAll`).
  Members-only videos are left out (they cannot play) and counted in the log.
- 🔀 in the header of the channel page and the podcast page; a **Shuffle all** row under the feed
  chips when a group is selected. "Include Shorts?" is asked whenever a channel is involved; a
  podcast-only shuffle starts straight away. The load runs in the app scope, so leaving the page does
  not cancel it, and the button shows the running count.

## Consequences

- Playback starts once every page has loaded (a large channel can take tens of seconds; the count
  shows progress), not at the first page. A uniform shuffle needs the whole list.
- A huge channel goes into the queue in full, as Dewi asked; the queue's own group collapse is what
  keeps it manageable. Reports list a window of 250 titles around the playing item, with the counts
  still exact, so a 3,000-item queue does not make a report megabytes long.
- Queue auto-download treats a shuffle like any other queued item and downloads all of it (on
  Wi-Fi only, if that setting is on). Dewi chose this on 2026-10-13 over keeping only the next ~20
  or never downloading shuffle groups.
- Logged under `[shuffle]`: sources, pages, items, unfinished parts, members-only left out, time taken.

## Amended 2026-10-14: an artist's songs

Dewi, 2026-10-14: *"the shuffle button on the artist page … shuffle all songs of the artist or?"*. It was
YouTube Music's Shuffle station (an endless radio: 9 of 49 songs by Erik Satie, because the button's `params`
were dropped). The artist page now has both: **Shuffle all songs** walks every page of the artist's All songs
listing (`walkAllSongs` in `music/ArtistShuffle.kt`: every song once, ending at the last page, a failed page,
or a page that adds nothing new) into one shuffled `artist-all:` group, recorded in Recently as
`Pin.ArtistSongs`; the All songs page's Shuffle uses the same walk (it had shuffled only the pages on
screen). **Shuffle radio** sends the button's `params` (`RadioSeed.params`). Live on the emulator: Erik Satie,
149 songs over 2 pages in 1.0 s; the station request carried `params=wAEB8gECGAE%3D`.

## Amended 2026-10-14: a 🔀 on every group

Dewi, 2026-10-14: *"I want a shuffle icon on each group please - that shuffles and plays"*, for both kinds of
group. Every group chip on the Videos tab now has its own 🔀 beside it, the same `ShuffleAllAction` (compact)
as a channel page, so a group no longer has to be selected first; the wide **Shuffle all** under a selected
group stays. Queue groups get theirs in ADR 40.


## Amended 2026-10-22: the item playing joins the group it was asked for with

When a "play all" with a group (shuffle all, an album, an artist's songs, a playlist) includes the item already
playing, that item moves into the new group instead of staying in its old one and appearing twice. Found as
"Shuffle all on an artist duplicates the song playing" (93ac61c6, `QueueGroupPlaybackTest` seen red first). It
applies to every grouped play-all, deliberately: the playing item is part of what was just asked for.
