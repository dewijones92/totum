---
title: Seamless Totum playback across devices
kind: todo
status: refining
area: playback/integration
priority: undecided
requested: 2026-10-05
updated: 2026-10-05
---

# Totum content wherever Dewi wants to play it

**Ask (Dewi, 2026-10-05):** an easy way to view Totum content on a TV, laptop,
web browser or wherever, with a seamless experience like Netflix.

The desired outcome is convenient access to the same Totum content on the screen
Dewi chooses, with little setup or friction. Treat this as one experience across
videos, podcasts and other supported media, using Totum's existing shared seams.

## Refine before implementation

- Which devices should work first, including the actual TV and laptop/browser?
- Should the phone send playback to another screen, should each device browse
  Totum independently, or should both be possible?
- What should follow between devices: the current item and playback position,
  queue, playlists, subscriptions or history? Picking up where Dewi left off is
  a candidate part of the Netflix-like experience; confirm the intended scope.
- Should this work only at home or away too, and what setup feels acceptable?

Choose the approach after refining these outcomes. Casting, a browser front end
and native TV/desktop interfaces are possibilities, not decisions already made.
Reuse the extraction and playback rules rather than creating a separate copy
for each device. This is for Dewi's own devices, within Totum's personal-app scope.

## Existing related work

- [Command-line front end](../features/command-line.md): existing desktop access
  sharing Totum's extraction stack; a starting point to assess, with no claim
  that it already provides the requested experience.
- [Progress sync](../features/progress-sync.md): assess what existing resume
  behaviour can contribute across devices and across media sources.

**Done when:** Dewi can easily find and play Totum content on the agreed devices,
with the agreed continuity when moving between them, verified on those devices.
