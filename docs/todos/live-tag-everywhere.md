---
title: 🔴 LIVE everywhere, kept up to date
kind: todo
status: built 2026-11-06, Batch A (tests written 2026-11-07)
batch: A (2026-11), phase 4
priority: medium
updated: 2026-11-07
---

# 🔴 LIVE everywhere, kept up to date

Dewi 2026-11-06: *"tag live ones like [members-only] … I want consistency"* (CLAUDE.md "Consistent everywhere",
ADR 108). The 🔴 LIVE tag exists (`tag_live`, `MediaItemRow.kt` ~338 for `MediaContentKind.LIVE`, beside 💎 MEMBERS)
but appears only where the source says the item is live (e.g. `FeedVideoMapping` for the account feed); RSS-based
group lists, items queued before going live and finished streams are wrong or untagged.

Decided: every list a live item can appear in (search, channel pages, group pages, the queue, the subscriptions feed,
notifications, the player, Ask cards) shows the same tag; Totum learns "live" whenever it looks a video up (resolve,
the watch page, the history poll) and stores it on the item through the existing fact-learning path
(`learnFacts`/`adoptFacts`, the same as dates and artists), so every row of that item updates; an upcoming one shows
🔴 STARTS SOON (built today); a stream that has ended becomes an ordinary video tagged "⏺️ Was live" and plays
normally (speed and silence cutting allowed again); the sort-and-filter sheet (ADR 66, `ItemFilters`) gets a
"🔴 Live now" filter. Log every kind change (was, now, why).

## Built 2026-11-06 (code first; tests owed)

See [live-streams.md](../features/live-streams.md) and [ADR 132](../adr/0132-the-live-tag-is-read-from-one-registry-and-kept-true.md).
Everything in "Decided" is built except the point below. The seam is `LiveStates` + `liveTagOf` (`:core:video`), the
old `UpcomingLives` widened; the ended state is the new `MediaContentKind.WAS_LIVE` (no schema change); the refresher
re-asks only what is tagged and on screen or queued.

My call: the doc said Totum learns live from "resolve, the watch page, the history poll". Built: resolve and the live
check (every look-up of a video goes through them). The watch page and the history list carry no live flag the code
reads, so they were not hooked.

The All subscriptions page's latest-upload line was added the same day (the coordinator's call; Dewi delegated it,
2026-11-06): `LiveBadge` on the row's latest item. It is not filtered by Not for me (ADR 128).

## Tests (Batch A testing stretch, part A, 2026-11-07)

Written: `LiveKindsTest` (`afterSeeing` for every pair, `canBeLive`, `fillingSilenceFrom` returning the same instance when
nothing was learned), `LiveTagTest` (every phase against every kind), `TheLiveCheckSaysWhatIsHappeningTest`
(`liveCheck` live / ended / upcoming with the registry told / unknown / signed-in first, and `contentKindOf`),
`TheResolverTellsTheLiveRegistryTest` (a resolve tells the registry, a yt-dlp resolve does not turn live into ended,
the live check records what it found), `LiveRefresherTest` (`:app`: only tagged shown or queued ids, none within four
minutes, at most six oldest first, learned live / ended handed back, back-off while unknown and its schedule, a check
that throws), `ItemFiltersTest` (the live filter), `MusicPlaysPlainTest` (an ended stream plays as talk), with the
earlier `LiveStatesTest`, `LiveStatesPersistenceTest`, `VideoResolverFastPathTest` and `MediaContentKindTest`.
Instrumented: `BatchAScreensTest` (the badge for live, starts soon with and without a time, ended, saved as ended, none
for an ordinary video or a song) and the earlier `AllSubscriptionsListTest`. Seen failing with an ended check recorded
as live, `afterSeeing` not moving a live item to ended, and the re-check interval removed.

Still owed: the badge inside the real queue row, mini player and player header; the "Live now" filter on screen; a
real-device look that a queued stream flips to "WAS LIVE" within two refresh cycles.
