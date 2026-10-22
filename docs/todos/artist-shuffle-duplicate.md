---
title: Shuffle all on an artist duplicates the song playing
kind: todo
status: requested 2026-10-22, decided
area: music/queue
priority: high
requested: 2026-10-22
updated: 2026-10-22
---

# Shuffle all on an artist duplicates the song playing

Bug (Dewi, 2026-10-22): play one song from an artist page, then shuffle all the artist's songs, and the song playing appears again inside the group. `PlaybackQueue.playAll` removes queued copies of the run except the playing one, then inserts the whole run.
