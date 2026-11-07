---
title: Share and copy link on every item
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Share and copy link on every item

Dewi 2026-11-06. One item-menu action for every kind (`ItemActions`, `MediaItemSheet`), using `rememberCopy` and
Android's share sheet; groups have no URL, so they share their list of titles (ADR 108 exception to record).

## Built 2026-11-06

Share and Copy link are in every item menu through `ItemActions`; a queue group and a torrent pack share their list of
titles. Detail: [share-link](../features/share-link.md), [ADR 134](../adr/0134-share-and-copy-link-are-on-every-item-menu.md).

My call (the doc left it open): a torrent, and anything whose address is the Pi's, shares its title only, because the
Pi's stream is private to this phone and a token is never put in a link (ADR 90).

## Tests (Batch A testing stretch, part A, 2026-11-07)

`ShareLinkTest` (`:core:domain`, 10: canonical watch address from the media address or the id alone, a Short, a live
address, an embed, a torrent by source or by id, a podcast address, an address on the Pi compared ignoring case, no
address, every answer saying why, the message), `HttpUrlTest` (`host`: no port, case kept, an ip), instrumented
`BatchAWordsTest` (`shareListMessage` numbering, the 100 cap, "and N more" singular and plural, a shielded title).
Seen failing with the Pi comparison made case-sensitive.

Still owed: instrumented, the item sheet offering Share for every kind and Copy link only with a link, and the queue
group and torrent pack menus offering the list share.
