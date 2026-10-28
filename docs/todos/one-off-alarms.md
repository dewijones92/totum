---
title: One-off alarms, set in two taps
kind: todo
area: side-quest
priority: high
status: built 2026-10-28 (ADR 91), awaiting Dewi on the phone
updated: 2026-10-28
---

# One-off alarms

**Ask (Dewi, 2026-10-28):** *"I want a way to easily set alarms. So for example, hey, I want to set an alarm
for seven PM tonight, titled deployed to production … easy to create in our app, we have an alarm screen …
a new page in that or a new plus button or whatever."* No voice entry.

## Decisions (Dewi, 2026-10-28)

| Question | Answer |
|---|---|
| How it is created | A ➕ New alarm button on the alarms page opens a quick sheet: title, time chips (+15 m · +30 m · +1 h · +2 h), a clock for any time, Today / Tomorrow / a date, Set |
| After it rings | Removed from the list once dismissed, but its title is kept as a **recent**, so "Deployed to production" can be set again in one tap |
| Reachable from | The alarms page, a home-screen icon shortcut (long-press Totum), a Quick Settings tile, and an Alarms tile on the Library hub |
| The ring | The same full-screen ring as the daily alarms (tone, vibration, the voice saying the title and the time, Snooze / Dismiss, playback paused), one ring path for both kinds |
