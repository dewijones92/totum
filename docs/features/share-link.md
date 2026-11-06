---
title: Share and copy link on every item
kind: feature
status: built 2026-11-06, Batch A (tests owed)
area: app
updated: 2026-11-06
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

## Tests owed

- `ShareLinkTest` in `:core:domain`: YouTube by media address, by id only, a Short and a `youtu.be` address
  canonicalised, a torrent, a podcast enclosure, an address on the Pi's host, no address, host compared ignoring case.
- `HttpUrlTest`: `host`.
- `shareListMessage` (Robolectric-free `Resources` fake or an instrumented check): numbering, the 100 cap and "and N more".
- Instrumented: the item sheet shows Share for every kind and Copy link only when there is a link; the queue group menu
  and a torrent pack menu show the list share.
