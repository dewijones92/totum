---
title: The live tag is read from one registry and kept true
kind: adr
status: accepted
updated: 2026-11-06
---

# 132. The live tag is read from one registry and kept true

- Status: Accepted
- Date: 2026-11-06

## Context

"🔴 LIVE" existed only where a list's own parser said an item was live (the account feeds, search). Items that came from
channel RSS (group pages), items queued before they went live, and streams that had ended all said the wrong thing, and
"🔴 Starts soon" (ADR 69, amended) was held in a registry that only `MediaItemRow` read. The player header, the mini
player, the facts card, notifications and the sort-and-filter sheet each knew nothing about it. Dewi (2026-11-06): tag live
ones like members-only, and "I want consistency" (ADR 108).

## Decision

- **One registry, one function.** `LiveStates` (`:core:video`, the old `UpcomingLives`) holds a phase per video id:
  `UPCOMING` (with the scheduled start), `LIVE` or `ENDED`. `liveTagOf(kind, state)` is the single rule that turns an item's
  stored kind plus the registry into `LiveTag.Live`, `StartsSoon(startsAt)`, `WasLive` or nothing; the registry wins over the
  stored kind because it is newer. Every surface reads `liveTagOf` (the UI through `liveTagFor` and `LiveBadge`, the
  media notification through `PlaybackContext.live`, the new-video notifications through `LiveNoticeLabels`, the filter
  sheet through `ItemFilters.accepts(…, liveNow)`); none carries its own check.
- **A stream that ended is a kind.** `MediaContentKind.WAS_LIVE` is an ordinary video that still says it was live:
  it plays with the talk tuning (speed and silence cutting allowed again) and is tagged "⏺️ WAS LIVE". It is stored as
  the kind's name in the existing column, so there is no schema change and an older backup reader falls back to STANDARD.
  `MediaContentKind.afterSeeing` is the one transition table: STANDARD takes whatever the resolution says (as before),
  LIVE becomes WAS_LIVE only on positive evidence of an ended stream, WAS_LIVE becomes LIVE if it is live again, other
  kinds never change. It is applied by `fillingSilenceFrom`, so the queue's `adoptFacts` and the download store's
  `learnFacts` persist it with no new path.
- **Learned whenever Totum looks a video up.** `VideoResolver.resolve` tells the registry the kind of every resolution
  (`contentKindOf`, now shared with `PlayerStreams`: live segments mean LIVE, `isLiveContent` without them means WAS_LIVE,
  otherwise STANDARD). yt-dlp answers carry no live evidence, so a yt-dlp resolve never turns LIVE into WAS_LIVE. A live
  check (`LiveCheck.Ended`, new) distinguishes a recording from a stream that is live now; before this a playable answer
  was always read as "went live".
- **Kept true, politely.** `LiveRefresher` re-asks `VideoResolver.liveCheck` (the signed-in TV client then the anonymous
  player, both through the polite gate of ADR 51, never yt-dlp) about items that are tagged live or starting soon and are on
  screen (a row, the player or the mini player registers interest while it shows the tag) or queued. A cycle is every
  5 minutes after a 2 minute start, at most 6 videos, none re-asked within 4 minutes, and the interval doubles (up to
  80 minutes) while whole cycles get no answer. A queued item that is learned LIVE or WAS_LIVE is written back through
  `adoptFacts` and `learnFacts`. Items that are not on screen or queued are never polled.
- **A new filter**, "🔴 Live now" in the Kind row of the sort-and-filter sheet (ADR 66), reads the same tag.
- **Every change is logged** (`[live]`: the video id, new phase, was, via what; each refresh cycle and each answer).

## Consequences

- Playlists, history and the feed cache keep the kind they were saved with; their rows show the registry's newer answer
  while the process lives and correct themselves the next time the refresher asks about them on screen. The refresher
  writes back only queue rows and downloads.
- An item stored STANDARD (RSS group pages) is never polled, so it learns "live" only when something resolves it
  (a tap, the menu-open and next-in-queue look-ups of ADR 11). No new YouTube traffic was added for those.
- The waiter (`LiveStartWatcher`) treats a playable non-live answer as "playable now" (its old behaviour) and records the
  stream as ended, so the registry is right even though the notification still says "is live".
- The registry is in memory; a restart drops it and the stored kind speaks until the next look-up.
