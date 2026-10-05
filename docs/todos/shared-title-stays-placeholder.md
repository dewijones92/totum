---
title: An offline shared link kept its placeholder title after downloading
kind: todo
status: fixed 2026-10-05
area: queue/downloads
priority: high
updated: 2026-10-05
---

# An offline shared link kept its placeholder title after downloading

Report `20261005T062054-903c8da3`, v0.1.558 / `73b5db8`: the initial description failed on DNS,
then the audio downloaded successfully. Local playback bypassed the only metadata adoption path.

Background repair now revisits placeholders in both queue and downloads after connectivity returns,
without delaying local playback or fetching the media again. Details and coverage live in
[shared-link metadata](../features/shared-link-metadata.md).
