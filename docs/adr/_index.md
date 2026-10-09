---
title: Architecture decision records
kind: index
updated: 2026-10-09
---

# Architecture decision records

One file per decision: `NNNN-short-title.md`, with status, date, context, decision and
consequences (the same shape as Loquax's `docs/adr/`). **Create, update or supersede the relevant
ADR in the same change as the code** (see `CLAUDE.md`, Living docs). A superseded ADR stays, with
its status changed to `superseded by NNNN`, so the history of why still reads end to end.

Decisions made before 2026-10-01 live in the Decisions table in `CLAUDE.md`. That table remains the
one-line summary of every standing decision; a row whose decision has an ADR links to it.

| # | Decision | Status |
|---|---|---|
| 1 | [Record decisions as ADRs](0001-record-decisions-as-adrs.md) | Accepted |
| 2 | [Exsurge et Disce is a side-quest module: a pure brain plus Android adapters](0002-exsurge-side-quest-module.md) | Accepted |
| 3 | [Exsurge's voice is an alarm, not media](0003-exsurge-voice-is-not-media.md) | Accepted |
| 4 | [Playback gets setPlaying and an interruption that resumes only what it paused; Continue Totum explicitly resumes during a walking break](0004-playback-set-playing-and-interruption.md) | Accepted |
| 5 | [Exsurge wakes with exact alarms, and a health foreground service holds the step counter](0005-exsurge-alarms-and-the-sticky-banner.md) | Accepted |
| 6 | [The takeover uses a full-screen intent, plus "display over other apps" when granted](0006-exsurge-takeover-over-other-apps.md) | Accepted |
| 7 | [Exsurge's tests run only when its area changes](0007-exsurge-tests-run-only-when-touched.md) | Accepted |
| 8 | [An unproven break earns no laurel](0008-an-unproven-break-earns-no-laurel.md) | Accepted |
| 9 | [Video fullscreen follows phone rotation; the button locks landscape](0009-rotation-follows-video-fullscreen.md) | Accepted |
| 10 | [The Exsurge break length is chosen at the summons and fixed for that break](0010-break-length-is-chosen-at-the-summons.md) | Accepted |
| 11 | [Switching to video is made fast by caching and readying, not by changing the extractor](0011-make-switching-to-video-fast.md) | Accepted |
| 12 | [YouTube's JS challenge is solved in Android's V8, with the player kept loaded](0012-solve-youtube-challenges-in-v8.md) | Accepted |
| 13 | [A fresh YouTube URL is waited for, a preloaded source is played, and a copy on disk is used first](0013-playback-start-young-urls-held-bytes-and-copies.md) | Accepted |
| 14 | [The playback service stays in the foreground for two hours after playback stops](0014-playback-service-stays-foreground-after-a-pause.md) | Accepted |
| 15 | [The Exsurge banner is an Android 16 Live Update, so it shows on the lock screen](0015-exsurge-banner-is-a-live-update.md) | Accepted |
| 16 | [Downloads keep their sponsor segments; playback skips them live from the latest list](0016-downloads-keep-sponsor-segments.md) | Accepted |
| 17 | [A reminder kit shared by the side quests](0017-reminder-kit-shared-by-side-quests.md) | Accepted |
| 18 | [Daily alarms — asked each morning, rung by Totum itself](0018-daily-alarms.md) | Accepted |
| 19 | [The Videos feed is hidden until asked, and hides again when you leave the tab](0019-videos-hidden-until-asked.md) | Accepted |
| 20 | [Gapless queue — the player holds the next item, and the queue adopts the crossover](0020-gapless-queue-the-player-holds-the-next-item.md) | Accepted |
| 21 | [Daily alarms are shown on one pinned board, soonest first](0021-alarm-board.md) | Accepted |
| 22 | [Every row says whether you follow its channel or show, and the mark subscribes](0022-every-row-says-whether-you-follow-its-source.md) | Accepted |
| 23 | [The Exsurge banner and the alarm board alert silently, so they show on every lock screen, and the board is always pinned](0023-pinned-notifications-alert-silently.md) | Accepted |
| 24 | [A Music tab takes Search's place in the bar; search is an action in every header](0024-music-tab-and-search-in-every-header.md) | Accepted |
| 25 | [Music plays as sound unless you ask for the picture](0025-music-plays-as-sound.md) | Accepted |
| 26 | [Albums, artists and your library come from YouTube Music; Play inserts after what is playing](0026-albums-artists-and-the-youtube-music-library.md) | Accepted |
| 27 | [Radio tops itself up; repeat and shuffle are queue features, for both pillars](0027-radio-repeat-and-shuffle-are-queue-features.md) | Accepted |
