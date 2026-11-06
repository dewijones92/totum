---
title: A group page shows its last list while refreshing
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# A group page shows its last list while refreshing

Report 20261103T193146-627bd592: a group page was blank for ~13 s. `GroupFeed` keeps lists in memory only. Decided:
persist them (the `GroupNewsWiring` temp-file-and-rename pattern), show them under "⏳ Refreshing", filtered by the
current members (`lastKnown`).
