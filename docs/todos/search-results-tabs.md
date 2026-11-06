---
title: Search results as tabs
kind: todo
status: built
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Search results as tabs

Report 20261101T232057. Tabs (Videos · Torrents · Audiobooks · Podcasts · Songs · Albums/Artists) with counts and a
still-searching dot, over the existing sections (`SearchScreen.kt:323-392`, `SearchSections.hitSection`); reuse the
player's `SecondaryScrollableTabRow` (`PlayerTabs.kt:66-83`). Paged counts must read as partial ("40+").

Whole-batch review: extract a generic tab row from `PlayerTabs` (it is bound to the `PlayerTab` enum) and use it in both.

Built 2026-11-06: see [search-sections](../features/search-sections.md) ("Results as tabs"). `ScrollableTabs` (ui/common) now serves the player and search; tests still to write are listed there.
