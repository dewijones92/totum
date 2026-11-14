---
title: Resuming a paused item takes YouTube's newer place
kind: todo
status: built; tests written
batch: A (2026-11)
priority: high
updated: 2026-11-14
---

# Resuming a paused item takes YouTube's newer place

Report 20261106T122148 (0.1.859): watched to ~66-80% in Chrome; the 5-minute history poll saw 1501s/2275s but the
paused item resumed from Totum's 1040s, then reported 965-1025s back to the account. The account position is compared
only when an item starts (`ResumeChoice.resumeFrom`). Decided: on resuming a paused item (and on reopening), if the
account is ahead and newer, jump there and say so ("▶️ Carried on from 25:01, watched elsewhere"). Deliberate rewinds
still reach the account: `HttpYouTubeWatchHistoryTest.kt:175-193` pins that and stays. `AccountProgress` has no time,
so "newer" uses `remoteAlreadyUsedMs` and the poll's arrival.

## Built (2026-11-06)

`ResumeRewind` calls back when a paused item plays again unmoved after 3 s or more (and on the first play after a
reopen on a paused item); `Media3PlaybackController` asks `PlaybackProgressStore.watchedFurtherElsewhereMs`, which
`AccountResumePositions` answers with `resumeFrom` and the player's position as the local side, records as acted on
when it wins, and the controller seeks and emits `PlaybackEvent.CarriedOn`; `PlaybackQueue` raises
`QueueNotice.CarriedOn` for the existing toast. Details, log lines and the tests owed are in
[progress-sync](../features/progress-sync.md); ADR 57 carries the decision. Not seen on a device. The reopen case rests
on the controller reading a paused item from the connected service at connect time, which is unconfirmed.

## Tests written 2026-11-14

`AccountResumeCarriesOnTest` (ahead and newer carries on and is recorded and adopted; behind, within a percent, finished here, finished on the account, the 10% floor, already acted on and never heard of do not; a moved figure carries on again; offline uses what is held; an account of 1,207 positions). Owed: the `ResumeRewind` callback cases and the controller seek (need a Media3 player), and a device check.
