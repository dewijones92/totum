---
title: The preloader's held copy should carry the next item's caption files
kind: todo
status: done 2026-10-23
area: playback
priority: low
requested: 2026-10-23
updated: 2026-10-23
---

# The preloader's held copy should carry the next item's caption files

Since ADR 78 a captioned next item does not take the held 30 s copy (it would drop its captions), so the preload is
wasted for most YouTube videos. Passing the subtitle tracks with `preloadNext` into `NextItemPreloader.hold` would let
the held source be built with them, and `takeFor` could then compare them as it compares the stream URLs.

Done 2026-10-23 (ADR 78): exactly that. On totum-api35, YnI-e_S4ZNw was held "with 22 caption file(s)" and then
played "from the source held", English captions on.
