---
title: A spoiler shield for sport
kind: todo
status: built 2026-11-07, Batch A (tests written 2026-11-07; device checks owed)
batch: A (2026-11)
priority: medium
updated: 2026-11-07
---

# A spoiler shield for sport

Dewi 2026-11-05 (agreed idea): for chosen channels (ATP, WTA, Tennis TV), Haiku rewrites titles spoiler-free, thumbnails blur and durations hide until watched. A per-source switch; the real title stays a tap away; dry runs on real titles.

Whole-batch review: it owns the shared title display policy, so every surface (rows, Next line, digest, Carry on, share, Ask) shows what the shield decides; the real title stays on the item.

## Built 2026-11-07

The shield owns the title seam: `ItemShield`/`ItemFace` in `:core:domain`, `SpoilerShield` in `:core:data`, read by rows, the
action sheet and Details, Carry on, the playback session (mini player, full player, notification, lock screen), the Next
line, the starting bar and refused toast, Share, Copy link, Share list, the not-for-me toast and the new-video
notifications. A per-channel switch (the channel page's 🙈 and the action sheet), real title one tap away, "watched"
means played. A `spoiler` AI job on the Pi (no tools), checked in code (`SpoilerRules`) on the Pi and again on the
phone, failing closed. Dry-run on 104 real titles; a plain "A vs B" title leaks the winner by name order, so names are
always put in alphabetical order.

Decisions, dry runs and limits: [ADR 141](../adr/0141-a-spoiler-shield-hides-results-in-titles-pictures-and-lengths.md); the feature doc: [spoiler-shield.md](../features/spoiler-shield.md).

## Tests

Exist (JVM): `SpoilerRulesTest` (words, scores, initials, `decide`); `SpoilerTitlesTest` (25 written titles in the shape of
the dry run: for every title, every model verdict and every offered rewrite, the title that would be shown never names a
result; titles that state one are caught by a rule rather than left to the model; titles that only name the players are not
taken for results); `SpoilerShieldTest`; `SpoilerShieldLimitsTest` (sixty titles asked 25 at a time with none twice or
dropped, the 3,000-title cap keeping the newest, an item turning plain the moment it is watched and hiding again, a title
revealed by hand surviving a restart and hiding again surviving one, `FileShieldStore` round trip, a corrupt or missing file,
an unwritable path, a channel matched by id or by name); `ItemShieldTest`; `NextLineTest` (the Next line through
`titleOf` in the plain, repeat-group and repeat-queue branches); `PlayAttemptsShieldTest` (the starting bar and the refused
tap named by the safe title). Seen failing: the batch size and the cap changed.

Instrumented, compiled and not run: `SpoilerShieldSurfacesTest` (`shareListMessage(titleOf)` and `LiveNoticeLabels.titled`,
which need a `Context`).

Still owed, with the reason:

- The dry-run title file is not in the repo, so `SpoilerTitlesTest` uses titles written for it; adding the real dry-run
  titles as a fixture file is still owed.
- Compose: a row of a shielded item shows "🙈 Title hidden", a blurred picture and no length; the sheet's reveal, hide again
  and channel actions; the channel page's toggle; Carry on; Details with the description and length gone. Needs a device.
- `metadataFor` gives the safe title, no description and no artwork for a shielded item: it is private to
  `Media3PlaybackController` and needs a real `MediaController`, so it can only be checked on a device.
- Nothing yet proves that **every** surface reads the seam: a new surface that reads `item.title` directly would not be
  caught by any test. A scan for it (a lint rule or a source test over the UI package) is not written.
- A device check: shield one channel on the phone, open its page, play one video, look at the notification and the lock
  screen, reveal one.
