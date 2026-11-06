---
title: The PC screen knows both phones
kind: todo
status: built 2026-11-06, Batch A (tests still to add)
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

## Built 2026-11-06

`screens.json` holds one entry per install id; `/screen` redirects to the only fresh phone, or the only one playing,
else a chooser (`/screen?choose=1` forces it, and the phone's page links to it); `ScreenAnnouncer` sends install id,
model, playing (`wantsToPlay`) and title. Entries are still unauthenticated (device token and the Pi serving the page
itself come with phase 7). See ADR 93 (amended) and the crash-log server README.

Tests still to add (code-first stretch): `test_screen.py` for the decision table (one fresh, two fresh with one playing,
two idle, stale only, `choose`), chooser escaping of model and title, the 8-phone cap, `legacy` and `unknown` handling;
`ScreenAnnouncerTest` for a re-announce when the playing flag or title changes.
