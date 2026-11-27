---
title: 🧸 The treat button, a film on the TV for a while, then off
kind: todo
status: built 2026-11-27, Batch C task V (ADR 196; unit tests; not run on a real TV)
priority: medium
updated: 2026-11-27
---

# 🧸 The treat button

Dewi, 2026-11-27: "Toy Story on the TV for 15 minutes, then off", configurable. Decision and design:
[ADR 196](../adr/0196-the-treat-button-puts-a-film-on-the-tv-for-a-while-and-the-pi-ends-it.md). Feature:
[treat-button](../features/treat-button.md).

## Reuse

Kid TV's shape (`KidTv`, its store, its tile, the Drive backup extra), the Pi targets (`TargetsRepository.queue` and
`control`), the sleep timer on the Pi (`DeckSleep` with one more flag, `stop`), the progress strip (`Operations` with a free
text detail and a `TREAT` kind), pins as the name for "what to play", and `TargetState.canSwitchOff` for the fall back.

## Owed on the real devices

Nothing here has touched the real LG. Dewi runs once: the tile with Toy Story (a pinned Jellyfin item) on the LG with 5
minutes, to see it play, fade, pause and switch off, and the strip and notification count down; then the same with the phone
locked and the app swiped away.

## Not done

- A film shorter than the time leaves the TV on (the Pi releases the timer when the queue ends; ADR 196).
- A TV the Pi does not list (a phone-only cast route) is not offered; Kid TV still covers it.
- No Settings page entry and no Quick Settings tile; the Library tile and the app-icon shortcut are the doors.
