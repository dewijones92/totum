---
title: BBC programmes
kind: feature
status: built
area: search
updated: 2026-11-27
---

# BBC programmes

**Ask (2026-11-20):** follow BBC programmes ("Have I Got News for You") like YouTube channels and podcasts, see "🆕" when an
episode arrives, and tap it to open it in the BBC iPlayer app. Agreed exception: the episode opens in iPlayer, it does not
play in Totum. Decisions and reasons: [ADR 184](../adr/0184-bbc-programmes-are-followable-sources-that-open-in-iplayer.md).

## What it does

- Search has a **📺 BBC** tab (Everything scope): programmes only, each with its channel, episode count and category. The
  row opens the programme's page; ➕ follows it.
- A programme's page is the podcast page: its newest 50 episodes with title (the series and episode, "Series 72: Episode 6"),
  the programme and channel, first broadcast date, length and **⏳ On iPlayer until 22 Dec 2027** (with ⏰ days left inside two
  weeks and ⌛ once gone). Tapping an episode opens iPlayer: the iPlayer app when it is installed, else the browser. A toast
  says which. **Opening it marks the episode played (✅ in every list, 2026-11-27, ADR 184)** once the other app or the browser
  took the link; if nothing could open it, nothing is marked. There is no Shuffle all and no Inbox toggle.
- A group-news notification for a new BBC episode opens that episode in iPlayer directly.
- A followed programme is in the Podcasts tab, can join groups, shows 🆕 in group news and the tab badge, notifies of new
  episodes, can be pinned and is in the backup, all through the podcast path.
- An episode's long-press menu has Star, Add to playlist, Share, Not for me, Details, Mark played and Go to podcast; it has no
  Play next, Add to queue, Peek, Listen/Watch, Download or Play on…, and it never enters the queue. A list that holds one
  plays the rest and skips it.

## Seam

`BbcProgrammes` (addresses) → `IblEpisodesParser` in `DefaultPodcastRepository` (a programme is a `PodcastFeed` at
`ibl.api.bbci.co.uk/ibl/v1/programmes/<pid>/episodes`) → `MediaItem` with `mediaUrl` = the iPlayer episode link and
`availableUntil` → `toPlayableOrNull` → `PlayHandle.External` → `routeNow` → `PlayRoute.External` → `PlaybackQueue`
(`openExternally`, wired to `ExternalAppOpener`). Search: `BbcSearchSource` → `SearchHit.Programme` → the podcast row.
`TimedCacheFetcher` keeps BBC answers for 15 minutes. Room v29 added `podcast_episodes.availableUntilEpochMs`.

## Logs

`bbc` (search query and counts, cache hits and misses with totals), `external` (every open: title, link, app or browser or
nothing), `queue` (`dewidebug … open in another app and are never queued`), `playback` (`dewidebug external …`), `podcast`
and `subs` (the ordinary feed lines, including `unparseable feed … no episodes for this programme right now`).

## Limits

- No playback, download or casting of BBC video, and no BBC Sounds radio.
- Only the newest 50 episodes of a programme.
- Whether the iPlayer app itself takes the link is not checked on the emulator (no iPlayer there); see the ADR.
- A programme with nothing on iPlayer right now fails to refresh with a reason and keeps what it had.
