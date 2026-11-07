---
title: One tap puts the kid playlist on the TV
kind: todo
status: built 2026-11-07, Batch A (tests written; not run on a TV)
batch: A (2026-11)
priority: low
updated: 2026-11-07
---

# One tap puts the kid playlist on the TV

Dewi 2026-11-05 (agreed idea): one button casts a chosen kid playlist to the LG in kid mode (1x, no silence skipping, no surprises; ADR 76). Tests use tennis, never kids' videos, on Dewi's TV.

## Built 2026-11-07 ([ADR 148](../adr/0148-kid-tv-is-kid-mode-on-the-tv-and-a-pinned-playlist.md))

A Library tile that starts or stops Kid TV with one tap, and a page (also in Settings) to choose a pinned playlist and a
TV. Kid mode now has two doors: the phone lock and the TV (`KidLock.modeOn`). See
[kid-tv-button](../features/kid-tv-button.md). Not run on a TV; no device was touched.

## Tests (Batch A testing stretch, part A, 2026-11-07)

`KidTvTest` (orchestrator, 18) and `KidModeTest` cover the rules; new instrumented `KidTvWordsTest` (every status and
failure sentence of the tile and page).

Still owed, with the reason: the tile and page interaction (needs a `KidTv` built on fakes inside an instrumented
test), `MediaRouterTvConnector` against a fake router (it uses the real `MediaRouter`; faking it needs a port first),
a debug broadcast, and the daytime run on the LG with tennis.
