---
title: ✨ Ask: the restore crash, and a chat list that hides the input
kind: todo
status: built
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# ✨ Ask: the restore crash, and a chat list that hides the input

Built 2026-11-06: one restore job (`restoreJob`) that `startOver` and `open` cancel, and `search` returns with a log
line when its turn is gone; the earlier chats show the latest 3 with "Show all" (a scrollable block, so the input
stays), and ➕ New chat is always there (disabled while busy). Tests owed: the restore/startOver interleaving seen
red against the old code, the chat list's cap and expansion.

Crash in two reports (20261101T131119-c956d084, 20261103T193146-7b2f90fc): `AskViewModel.restore()` launches
`search(it)` per turn while `startOver()` clears the turns (`AskViewModel.kt:85-95, 198-208`). Fix: one restore job,
cancelled by `startOver`/`open`; test the interleaving.

Report 20261105T064409: many earlier chats fill the screen. The recent chips (`RecentSearches.kt:96-102`, unbounded
`FlowRow`) show only with no turns and squeeze the input; New chat shows only with turns. Decided: show the latest 3
with "Show all", and New chat always visible.
