---
title: New uploads flow into their shuffled group, or into an inbox
kind: adr
status: accepted
updated: 2026-10-15
---

# 59. New uploads flow into their shuffled group, or into an inbox

- Status: Accepted
- Date: 2026-10-15

## Context

Dewi, 2026-10-15: he shuffles the whole Peep Show channel into the queue for mealtimes, and the channel keeps
uploading; *"is there a slick, elegant way … to pull in the new items from the channel to go in that group?"*. The
product review had also proposed a queue inbox (new items from chosen sources into the queue). Asked, he chose: one
mechanism for both; automatic and a manual pull; a new upload goes **next** in its group; every shuffle-all group,
both pillars; checks every two hours even with the app closed, **on Wi-Fi only**; inbox items in one "📥 New" group
at the end of the queue; the inbox switched on per source (page and Settings) or for every subscription.

## Decision

- `uploads/NewUploads.kt` is the one check. It keeps, per shuffle-all group in the queue (`WatchedGroup`), the
  shuffle that made it (`Pin.ShuffleAllOf`: sources, the Shorts choice) and every item id it has seen. A check reads
  each source's newest items (`LatestUploads`: a channel's public RSS feed, a podcast's feed; a channel known only by
  handle pages its uploads), and anything unseen goes next in the group through `PlaybackQueue.addToGroup(…, NEXT)`:
  straight after what is playing when the group is playing, else at the group's head. Newest first.
- A group that has left the queue is forgotten. A shuffle group from before this change is **adopted** from the
  Recently entry of its shuffle, with everything queued, played or started counting as seen, so only genuinely new
  uploads arrive.
- The inbox: the sources switched on (or every subscription) that no shuffled group in the queue covers. A source's
  first look only notes what exists; later uploads go to the end of "📥 New" (`inbox:new`). Shorts and members-only
  items are left out of the inbox; a group follows its own Shorts choice.
- When: `NewUploadsWorker` every two hours on an unmetered network, and 20 s after the app opens unless the network is
  metered (never under instrumentation). By hand: the group's ⋮ → 🔄 Pull new uploads, on any network, with a
  snackbar saying what happened. All requests go through the polite gate (RSS is about 6 KB per channel).
- State lives in `files/new_uploads.json` (not Room, so no schema bump); settings `inboxAll` and `inboxSources` are in
  `AppPreferences`, backed up and in reports.

## Consequences

- An inbox with every subscription on is one feed request per subscription every two hours on Wi-Fi.
- A channel feed lists its newest 15 uploads, so more than 15 between checks loses the oldest of them.
- Logs (`uploads`): every watched group's check (latest, new, added), each adoption, each inbox source's first look,
  every failure; reports carry `uploads.lastCheck`, `uploads.watchedGroups` and `settings.inbox`.
- Tests: `NewUploadsTest`, `AddedToGroupTest`, `PreferenceBackupTest`.
