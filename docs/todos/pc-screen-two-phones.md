---
title: The PC screen knows both phones
kind: todo
status: decided
batch: A (2026-11)
priority: high
updated: 2026-11-06
---

# The PC screen knows both phones

Dewi 2026-11-06: the private-area link opened the Pixel 4a, not the Pixel 7. `tools/crashlog-server/app/main.py`
keeps one `screen.json` (last announcer wins). Decided: one entry per phone keyed by install id (named by model,
renameable); `/screen` goes straight to the only phone, else a chooser, preferring the phone that is playing, with a
"switch phone" link on the page. `ScreenAnnouncer` adds install id and model, never the page key. `/ingest` is
unauthenticated, so keyed entries need the device token (ties to publishing). Tests: `tests/test_screen.py`,
`ScreenAnnouncerTest`.

Whole-batch review: phase 7 lets the Pi serve browsers itself. Dewi 2026-11-06: keep it small now (a per-phone entry on the crash-log server); phase 7 later points the bookmark at the Pi's own page.
