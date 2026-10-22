---
title: The Library is a hub, and downloads have their own page
kind: adr
status: accepted
updated: 2026-10-22
---

# 73. The Library is a hub, and downloads have their own page

- Status: Accepted
- Date: 2026-10-22

## Context

Dewi, 2026-10-22, on the settings and account pages: *"too much … reorganise a bit better. I trust your decision"*.
The Library tab was a header, the tiles, and then the whole download manager underneath them: running, failed,
the storage meter, the clean-up cards and every downloaded item. With 64 downloads the tiles were a short preamble
to a very long list, and Playlists, History, Subscriptions, Account and Stats were each a scroll away from it on the
way back.

## Decision

- The Library tab is a hub that fits on one screen: Recently, Pinned, then tiles for Stats, **Downloads**,
  Playlists, Subscriptions, History and Account, then phone albums and the build line (`LibraryHub`).
- Downloads is a tile like the others and opens its own page (`DownloadsScreen`), with a back header. The tile says
  what is there without opening it: "📥 64 on the phone · ⏳ 2 downloading · ⚠️ 5 failed" (`DownloadsSummary`).
- On that page the finished list is headed **On the phone**, so the page title is not repeated.
- The open page is saved (`LibraryPage`), and system back returns to the hub.

## Consequences

- Nothing about the download manager changed: the same sections, sorts, filters and clean-up cards, one tap deeper.
- A failed download is still visible without opening the page, because the tile counts it.
- `ScreensKeepEveryControlTest` opens every tile, Downloads included; `CleanupCardsTest` drives `DownloadsContent`.
