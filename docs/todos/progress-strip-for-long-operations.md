---
title: A live strip on every tab while something long runs
kind: todo
status: requested 2026-10-23, decided
area: ui
priority: high
requested: 2026-10-23
updated: 2026-10-23
---

# A live strip on every tab while something long runs

Report 0.1.686 (Dewi: *"the last bunch of ops took a while? maybe the UI needs to be better when indicating progress
of long running ops"*): shuffle all songs of an artist took 14 s, an artist page 23 s, with nothing on screen.
Decided 2026-10-23: **a strip under the header on every tab** while something long runs (shuffle all, artist and album
pages loading, preparing a torrent, an episode batch), with what it is, a running count and Cancel; it goes when done,
with a toast giving the result. The gate's slowness itself was eased the same day (ADR 51 update: three lanes).

Widened the same afternoon into a standing rule (CLAUDE.md, "Every async operation shows itself"): the strip is the
one seam **every** async operation the user starts reports to, not a list of four. First user: shuffle all, which now
plays at once and keeps filling in behind (report 0.1.694: Chopin, 201 albums, 116 s).
