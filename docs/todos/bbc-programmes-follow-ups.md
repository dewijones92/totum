---
title: BBC programmes follow-ups
kind: todo
status: open
priority: low
area: search
updated: 2026-11-20
---

# BBC programmes follow-ups

What [ADR 184](../adr/0184-bbc-programmes-are-followable-sources-that-open-in-iplayer.md) left out or could not confirm.

- **Seen on a phone with iPlayer installed.** The emulator has no iPlayer, so only the browser fallback was exercised. Check on
  the Pixel that a tap opens the episode in the iPlayer app and not in the browser (the intent is restricted to
  `uk.co.bbc.iplayer` first; the `external` log line says which took it).
- **BBC Sounds radio.** iBL covers iPlayer television only; radio programmes are a different API and would be a second parser
  behind the same `PodcastFeed` and external-handle seams.
- **More than the newest 50 episodes of a programme.** One request per programme is deliberate; a long-running show's older
  series would need paging (`page=2`) behind a "Show more" the page does not have.
- **An end date on starred and playlisted episodes.** Their item columns have no availability field, so they lose it; adding it
  is a schema change on four tables.
- **An episode iPlayer has withdrawn** disappears at the next refresh; nothing tells you it was there. A "left iPlayer" notice
  would need the old and new lists compared.
