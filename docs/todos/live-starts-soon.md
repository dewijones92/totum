---
title: A live stream that hasn't started says so, and calls you when it does
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# A live stream that hasn't started says so, and calls you when it does

Report 20261104T171409: tapping "Elina Svitolina | Live Practice" before it began logged `UNPLAYABLE: This live event
will begin in a few moments` (signed-in TV and every yt-dlp client) and the peek "did not start", with nothing on
screen. Decided 2026-11-06: the row and the player say "🔴 Starts soon" (or the scheduled time when YouTube gives
one), and Totum checks politely through the YouTube gate until it goes live, then notifies you; Cancel in the
progress strip. Reuse the resolve refusal reasons and the live route (ADR 69).
