---
title: Subtitles off stays off until turned on
kind: todo
status: built
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Subtitles off stays off until turned on

Report 20261101T225545; Dewi 2026-11-05: off stays off across items AND app restarts. Supersedes ADR 78's "off is
for one item". `CaptionPreference` becomes a persisted on/off (AppPreferences) instead of `offFor`;
`CaptionPreferenceTest.kt:19-24` inverts (seen red first).

Built 2026-11-06 as [ADR 123](../adr/0123-subtitles-off-stays-off.md): `CaptionChoice` in `AppPreferences`, `CaptionPreferenceTest` inverted. No Settings row was added (the CC menu is the switch); the tests still to write are listed in the ADR.
