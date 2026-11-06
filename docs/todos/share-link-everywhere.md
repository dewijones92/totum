---
title: Share and copy link on every item
kind: todo
status: built 2026-11-06, Batch A (tests owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Share and copy link on every item

Dewi 2026-11-06. One item-menu action for every kind (`ItemActions`, `MediaItemSheet`), using `rememberCopy` and
Android's share sheet; groups have no URL, so they share their list of titles (ADR 108 exception to record).

## Built 2026-11-06

Share and Copy link are in every item menu through `ItemActions`; a queue group and a torrent pack share their list of
titles. Detail: [share-link](../features/share-link.md), [ADR 134](../adr/0134-share-and-copy-link-are-on-every-item-menu.md).

My call (the doc left it open): a torrent, and anything whose address is the Pi's, shares its title only, because the
Pi's stream is private to this phone and a token is never put in a link (ADR 90).

## Tests owed

`ShareLinkTest` (every row of the table in the feature doc), `HttpUrlTest.host`, `shareListMessage` (numbering, cap),
and an instrumented check that the sheet offers Share for every kind and Copy link only with a link.
