---
title: The preloader's held copy should carry the next item's caption files
kind: todo
status: open
area: playback
priority: low
requested: 2026-10-23
updated: 2026-10-23
---

# The preloader's held copy should carry the next item's caption files

Since ADR 78 a captioned next item does not take the held 30 s copy (it would drop its captions), so the preload is
wasted for most YouTube videos. Passing the subtitle tracks with `preloadNext` into `NextItemPreloader.hold` would let
the held source be built with them, and `takeFor` could then compare them as it compares the stream URLs.
