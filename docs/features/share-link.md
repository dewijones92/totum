---
title: Share and copy link on every item
kind: feature
status: built 2026-11-06, Batch A (tests written 2026-11-07)
area: app
updated: 2026-11-07
---

# Share and copy link on every item

Dewi, 2026-11-06. [ADR 134](../adr/0134-share-and-copy-link-are-on-every-item-menu.md).

## What it does

Every item menu has **📤 Share** (the title, and the link when there is one, through the phone's share sheet) and
**🔗 Copy link** (only when there is one). A queue group's menu and a torrent pack's menu have **📤 Share the list of
titles**, because a group has no address.

| Kind | Shares |
|---|---|
| YouTube video, Short, live, song | the canonical watch address |
| Podcast episode, audiobook chapter | its own media address, unless that is on the Pi |
| Torrent, or anything whose address is on the Pi | the title only (the Pi's stream is private to this phone) |
| A group or pack | its title and a numbered list of titles (at most 100) |

## Seams and reuse

- `MediaItem.shareLink` (`:core:domain`, `ShareLink.kt`) is the one rule; `ItemActions.share/copyLink/shareList` are the
  one entry point, so `ActionSheet` and the queue group menu add nothing of their own.
- `rememberCopier` is the old `rememberCopy` split in two (the details section's copy button is unchanged);
  `rememberSharer` is new and both live in `LinksAndCopy.kt`.
- `HttpUrl.host` is new in `:lib:common`.

## Diagnostics

`share` lines (`dewidebug`) say what was shared, its id and why the answer was a link or a title only; a share with no
app to take it logs a warning and shows "⚠️ No app on this phone can take a share".

## Tests (Batch A testing stretch, part A, 2026-11-07)

`ShareLinkTest` (`:core:domain`, 10: canonical watch address from the media address or the id alone, a Short, a live
address, an embed, a torrent by source or by id, a podcast address, an address on the Pi compared ignoring case, no
address, every answer saying why, the message), `HttpUrlTest` (`host`: no port, case kept, an ip), instrumented
`BatchAWordsTest` (`shareListMessage` numbering, the 100 cap, "and N more" singular and plural, a shielded title).
Seen failing with the Pi comparison made case-sensitive.

Still owed: instrumented, the item sheet offering Share for every kind and Copy link only with a link, and the queue
group and torrent pack menus offering the list share.
