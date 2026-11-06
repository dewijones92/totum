---
title: Not for me
kind: todo
status: built
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Not for me

Dewi 2026-11-06: one tap removes an item from the queue and its source group never suggests it again. A suppression
store read by Explore, new uploads, group news and Ask; the remove half reuses the shared Undo.

## Built 2026-11-06

See [menus](../features/menus-and-source-pages.md) for which surfaces filter and which deliberately do not, and ADR 128.

Tests to add: `NotForMe` (suppress, allow, contains by canonical id, `mentions` squashing, merge, encode/decode round trip,
unreadable file); `LatestUploads.of` leaves a listed id out; the backup wrapper's export/restore; the menu action removing
a queued row and Undo putting both back; `AskViewModel` drops a matching suggestion.
