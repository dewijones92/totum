---
title: A rewind was overridden before the first account-aware resume
kind: todo
status: fixed 2026-10-05
area: playback
priority: high
updated: 2026-10-05
---

# A rewind was overridden before the first account-aware resume

Report `20261004T064739-53c7e60a`, v0.1.558 / `73b5db8`: seek to zero at 07:46:31 was saved;
switching back at 07:46:44 chose YouTube's cached 3,038,880ms with `alreadyUsed=none`.

The deliberate seek now records that cached account figure as overridden before saving locally.
It survives restart and still permits a changed account position. Details and coverage live in
[progress sync](../features/progress-sync.md#seeking-overrides-a-cached-account-figure-before-its-first-resume-2026-10-05).
