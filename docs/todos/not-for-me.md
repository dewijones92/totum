---
title: Not for me
kind: todo
status: built
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# Not for me

Dewi 2026-11-06: one tap removes an item from the queue and its source group never suggests it again. A suppression
store read by Explore, new uploads, group news and Ask; the remove half reuses the shared Undo.

## Built 2026-11-06

See [menus](../features/menus-and-source-pages.md) for which surfaces filter and which deliberately do not, and ADR 128.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`NotForMeTest` (`:core:data`, 15: suppress, canonical id for the watch address and the bare id, newest first,
allow, label squashing, `without` keeps order and the same list when empty, `mergeMissing`, encode/decode with unknown
keys, the file store round trip and an unreadable file), `NotForMeSurfacesTest` (a source's latest uploads without the
listed item, an unreadable feed staying unknown, the backup wrapper exporting, merging and ignoring a bad list),
`AskNotForMeTest` (a matching suggestion never reaches a card; the list is told to the model as already seen), beside
`NotForMeRejecterTest`. Seen failing with the id canonicalisation removed.

Still owed: the menu action on screen (remove a queued row, then Undo putting both back), Explore, group news and
Carry on filtering through their own wiring (Carry on is covered through `CarryOnViewModelTest`).

Calls the coordinator made for Dewi, who delegated them on 2026-11-06: 🙅 on the item playing now skips to the next item;
the All subscriptions page's latest-upload line is not filtered (a fact about your own subscription). See ADR 128.

