---
title: Subtitles off stays off until turned on
kind: todo
status: built 2026-11-06, Batch A (ADR 123; CaptionPreferenceTest inverted and extended in the same commit; the settings round trip, the backup restore of the two keys and a device check are still owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-14
---

# Subtitles off stays off until turned on

Report 20261101T225545; Dewi 2026-11-05: off stays off across items AND app restarts. Supersedes ADR 78's "off is
for one item". `CaptionPreference` becomes a persisted on/off (AppPreferences) instead of `offFor`;
`CaptionPreferenceTest.kt:19-24` inverts (seen red first).

Built 2026-11-06 as [ADR 123](../adr/0123-subtitles-off-stays-off.md): `CaptionChoice` in `AppPreferences`, `CaptionPreferenceTest` inverted. No Settings row was added (the CC menu is the switch); the tests still to write are listed in the ADR.

Status check (2026-11-14): the index said "tests to follow". `CaptionPreferenceTest` (`:core:playback`, committed with the feature in
`8aa79cf3`) now has the inverted "off stays off for every item", the "off survives a restart, read back from the store", "turning
them on brings back the last language" and "a chosen language carries on" cases. What the ADR lists under "Tests still to
write" (the `SharedPrefsAppPreferences` round trip of `captions`, the backup export and restore of the two keys, and a device
check across a restart and a gapless hand-over) has no test in the tree.
