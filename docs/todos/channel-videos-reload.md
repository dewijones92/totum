---
title: A channel's Videos reload when reopened
kind: todo
status: built
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# A channel's Videos reload when reopened

Built 2026-11-06: `ChannelViewModel.refreshVideosOnEntry()` (from `ChannelScreen`) reloads the Videos list when it is
5 minutes old or its last load failed, keeps the old list on screen with a progress bar meanwhile, keeps it if the
refresh fails, and logs the reuse and its age. Tests owed: reuse under 5 minutes, reload after, a failed refresh
keeping the list.

Report 20261101T222816: a 6-hour-old Tennis TV upload was missing from the channel list (hypothesis, the first load
was before the buffer). `ChannelScreen.kt:92-93` keys an Activity-scoped `ChannelViewModel` that loads once
(`:215-221, :244`). Fix: reload on entry when the list is older than a few minutes; log the reuse and the age.

Consistency sweep 2026-11-06: one rule for Videos, Shorts and Playlists. `ChannelViewModel.refreshOnEntry()` and
selecting an already loaded tab both apply it (5 minutes old, or the last load failed: the old list stays under a
progress bar, a failed refresh keeps it); one `reload` serves all three tabs, with the paging step sharing the same
`TabLens` per tab. Tests owed: Shorts and Playlists reuse under 5 minutes, reload after, a failed refresh keeping the list.
