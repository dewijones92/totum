---
title: One tap to switch between music and talk (podcasts, videos) in the queue
kind: todo
status: open — agreed with Dewi 2026-10-14; in the 2026-10-14 batch after the Pixel 4a work
area: queue/playback
priority: high
requested: 2026-10-14
updated: 2026-10-14
---

# Music ⇄ talk toggle

Dewi, 2026-10-14: *"I want a way to toggle between music and nonmusic … save me going in to the queue ui
each time"*.

## Agreed

- **Jump within the queue**, removing nothing: talk → music jumps to the next music item in the queue
  (or where music last left off); music → talk jumps back to the podcast or video left, at its saved
  position.
- **Everywhere**: mini player, full player, the media notification / lock screen, and the Queue header.
- **No music queued**: play the newest music action from Recently (album, radio, shuffle).

## Notes for the build

- One rule in `:core:domain` deciding the target from the queue, the playing item and the last item of
  each kind (music = `MediaContentKind.MUSIC`), unit-tested; one queue call both pillars use.
- The media notification shows at most three actions (`media3-late-notification-fgs-crash` notes);
  it already has rewind, play/pause and forward, so the toggle replaces one of them there. Ask Dewi
  which before shipping.
- Logged: from which item, why that target, and when nothing qualified.
