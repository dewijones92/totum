---
title: Watch the phone's current item on the PC from a private-area tile
kind: todo
area: casting
priority: high
status: decided 2026-10-29, not started
updated: 2026-10-29
---

# Watch the phone on the PC from the private area

**Ask (Dewi, 2026-10-29):** *"have the Totum app as the brain on the phone and be able to view its video via the
browser from the computer … the private area screen on my Raspberry Pi … I want an extra one … Dewi's Totum, I click
on that and then I can view … watch the current item playing … a service on the phone that does this if it's plugged
in on Wi-Fi."*

**What already exists (ADR 81):** the phone serves `/screen` (Shaka) on its cast server; the Google-gated
`https://crashlog.333133333.xyz/screen` redirects to it; but the PC must then be picked on the phone (🖥️), and the page
only answers while the playback service runs.

**His answers (AskUserQuestion, 2026-10-29):**
- On click: the page opens showing what the phone is playing; one big **▶️ Watch here** moves playback to the PC with
  sound (Edge needs that click for sound); the phone goes quiet and becomes the remote.
- Ready **whenever the phone is plugged in and on Wi-Fi**: a background service keeps the page up, with a quiet
  "🖥️ Ready for your PC" notification (verify Android's background-start limits on the emulator).
- Nothing playing: play the **queue's current item** from its saved position.
- A **"📱 Dewi's Totum"** tile on the private area landing page (dot-files on the Pi; read its CLAUDE.md first,
  commit there, show the diff) linking to the existing `/screen` redirect.
