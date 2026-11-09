---
title: Targets, end-of-batch fixes - loops, ids, a loopback listener, slow answers, a TV that is switched off
kind: adr
status: accepted
updated: 2026-11-09
---

# 158. Targets, end-of-batch fixes

- Status: Accepted
- Date: 2026-11-09
- Amends: [ADR 152](0152-a-target-is-anything-the-pi-plays-to-and-each-has-its-own-queue.md),
  [ADR 155](0155-a-target-repeats-plays-files-on-the-pi-and-says-why-it-could-not-play.md)

## Context

An Opus review and two sweeps of the Pi-targets batch (2026-11-07) found a handful of defects, each checked against the
code before it was fixed. The decisions were the coordinator's (Dewi delegated them). They are recorded here, one section
per finding.

## Decisions

### A loopback listener, not all of 8770 (finding 2)

ADR 155 published 8770 on the Pi's loopback so Home Assistant could call `/targets`. 8770 serves every route with no
token of its own (nginx's `auth_request` is the only guard): `/jobs`, `/files`, `/account`, `/ask`, `/jellyfin` as well.
`totum serve` now starts a **second listener** (`TOTUM_LOOPBACK_PORT`, default 8772) that answers `GET /health` and the
`/targets` routes and nothing else (404 and a warning in the log for anything else). Compose publishes
`127.0.0.1:8772:8772` and no longer publishes 8770; the container's own health check still uses 8770 inside the
container. The adapter's `TOTUM_URL` default is `http://127.0.0.1:8772`. Other containers on `bin_private` can reach 8772
as they could always reach 8770; the listener narrows what the host can do, not what the network can.
Tests: `LoopbackListenerTest`.

### Looping belongs to an entry, repeat to the deck (finding 1)

Home Assistant's brown noise sent `repeat: ONE` for the whole deck. Nothing reset it and the phone could neither see
nor change it, so the next podcast sent to that speaker looped its first item for ever. Now:

- `TargetQueueRequest.loop` marks the items of that request, and `TargetEntry.loop` carries it back in `GET /targets`.
  An entry that loops starts again when it ends, whatever the deck's repeat, with the same two guards as repeat (never
  after a failure, never after an item that ended within 2 s). It is remembered per item, saved with the deck, and
  replaced by the next request that names the item.
- The deck's `repeat` stays the user's choice (off, this one, the queue: `RepeatMode.onATarget`), set only by the
  `REPEAT` control or a request's `repeat`, and works as the phone's queue repeat does.
- The phone's Remote queue shows the deck's repeat with the player's own `RepeatTile` (the same picker, compact),
  limited to the modes a target honours, and "🔂 Loops" on an entry that loops.
- `tools/handoff/hermes-audio/audio-ctl.py` queues brown noise with `loop: true` and never sends `repeat`.
  It supersedes the stopgap on the deployed adapter (a REPEAT OFF on stop). A deck that was left on `ONE` by the old
  adapter keeps it until someone changes it, which the phone can now do.
- Tests: `TargetDeckTest` (loops, no inheritance by the next request, survives a restart, never repeats a failure,
  leaves the deck's repeat alone), `TargetRepeatTest`, `test_audio_ctl.py`.
