---
title: A group page shows its last list while refreshing
kind: todo
status: built; tests written
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# A group page shows its last list while refreshing

Report 20261103T193146-627bd592: a group page was blank for ~13 s. `GroupFeed` keeps lists in memory only. Decided:
persist them (the `GroupNewsWiring` temp-file-and-rename pattern), show them under "⏳ Refreshing", filtered by the
current members (`lastKnown`).

Whole-batch review: write with `File.writeTextReplacing` (`lib/common/ReplaceFile.kt`), and move `GroupNewsWiring` off its private temp-file copy at the same time.

## Built (2026-11-06)

See [group-page](../features/group-page.md). Also changed, because it would have wiped the persisted list on the first offline refresh: a source that cannot be read keeps its items from the last list.

## Tests written 2026-11-14

`GroupFeedKeptListsTest` (a list surviving a restart, a removed member not shown from it, a corrupt file starting empty, a failed member keeping its rows while one that reads fine and is empty does not, every member failing keeping the whole list, two groups loading together, a file that cannot be written, 300 channels of 15 videos), `FileGroupNewsStoreTest`. Owed: the "Refreshing" line (Compose) and a cold start on a device.
