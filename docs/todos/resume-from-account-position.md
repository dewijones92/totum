---
title: Resuming a paused item takes YouTube's newer place
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# Resuming a paused item takes YouTube's newer place

Report 20261106T122148 (0.1.859): watched to ~66-80% in Chrome; the 5-minute history poll saw 1501s/2275s but the
paused item resumed from Totum's 1040s, then reported 965-1025s back to the account. The account position is compared
only when an item starts (`ResumeChoice.resumeFrom`). Decided: on resuming a paused item (and on reopening), if the
account is ahead and newer, jump there and say so ("▶️ Carried on from 25:01, watched elsewhere"). Deliberate rewinds
still reach the account: `HttpYouTubeWatchHistoryTest.kt:175-193` pins that and stays. `AccountProgress` has no time,
so "newer" uses `remoteAlreadyUsedMs` and the poll's arrival.
