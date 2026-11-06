---
title: 🔴 LIVE everywhere, kept up to date
kind: todo
status: built 2026-11-06, Batch A (tests owed)
batch: A (2026-11), phase 4
priority: medium
updated: 2026-11-06
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

See [live-streams.md](../features/live-streams.md) and [ADR 131](../adr/0131-the-live-tag-is-read-from-one-registry-and-kept-true.md).
Everything in "Decided" is built except the point below. The seam is `LiveStates` + `liveTagOf` (`:core:video`), the
old `UpcomingLives` widened; the ended state is the new `MediaContentKind.WAS_LIVE` (no schema change); the refresher
re-asks only what is tagged and on screen or queued.

My call: the doc said Totum learns live from "resolve, the watch page, the history poll". Built: resolve and the live
check (every look-up of a video goes through them). The watch page and the history list carry no live flag the code
reads, so they were not hooked.

## Tests owed

- `LiveKindsTest` (`:core:domain`): `afterSeeing` for every pair (STANDARD takes the resolution, LIVE to WAS_LIVE only on
  WAS_LIVE, WAS_LIVE back to LIVE, SHORT/MUSIC/AUDIOBOOK never change); `fillingSilenceFrom` returns the same instance
  when nothing changed and a copy with the new kind when it did.
- `ItemFiltersTest`: `KindFilter.LIVE` uses `liveNow`, defaulting to `contentKind == LIVE`.
- `LiveStatesTest` and `LiveTagTest` (`:core:video`): each `note*`, `observed` clearing an upcoming entry on a plain
  resolve and not touching a LIVE one, the 12 h prune of upcoming and live and not of ended, the 500 ended cap;
  `liveTagOf` for every phase against every kind (SHORT/MUSIC/AUDIOBOOK always null).
- `ContentKindsTest`: live segments with `isLive` is LIVE, `isLive` without them WAS_LIVE, neither STANDARD.
- `PlayerStreamsTest`: `liveCheck` returns `Ended` for a playable non-live answer and `Live` for live segments.
- `VideoResolverTest`: a resolve tells the registry the kind; a yt-dlp resolve does not turn a LIVE entry to ENDED.
- `LiveRefresherTest` (`:app`): only tagged shown or queued ids are asked, none within 4 minutes, at most 6, back-off
  while every answer is Unknown, a queued item learned WAS_LIVE is handed to `onLearned`.
- `MusicPlaysPlainTest`: WAS_LIVE plays with the talk choices.
- A Compose check that a queued ended stream and a group-page upcoming one show "⏺️ WAS LIVE" / "🔴 STARTS …" in the row,
  the mini player and the player header, and the "🔴 Live now" filter keeps only live rows.
- A real-device look: a live stream queued, then ended, flips to "⏺️ WAS LIVE" within two refresh cycles.

