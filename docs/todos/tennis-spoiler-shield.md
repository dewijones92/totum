---
title: A spoiler shield for sport
kind: todo
status: built 2026-11-07, Batch A (tests owed)
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

## Tests owed

- `SpoilerRulesTest` exists (words, scores, initials, `decide`); owed: more real titles from the dry run as fixtures, and
  a test that every title in the dry-run file ends up either unchanged or free of `SpoilerRules.reveals`.
- `SpoilerShieldTest` exists (hidden at once, asked once, retry time, no verdict, persistence); owed: a batch over 25 is
  split, the 3,000-title cap, `FileShieldStore` (round trip, a corrupt file starts empty), `watched` flipping, a revealed
  id surviving a restart.
- Compose: a row of a shielded item shows "🙈 Title hidden", a blurred picture and no length; the sheet's reveal, hide again
  and channel actions; the channel page's toggle; Carry on; Details with the description and length gone.
- Unit: `nextLineOf` with `titleOf`, `PlayAttempts` with a shield, `LiveNoticeLabels.titled`, `shareListMessage(titleOf)`.
- Instrumented: `metadataFor` gives the safe title, no description and no artwork for a shielded item.
- A device check: shield one channel on the phone, open its page, play one video, look at the notification and the
  lock screen, reveal one.
