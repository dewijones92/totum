---
title: Architecture decision records
kind: index
updated: 2026-10-31
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
| 28 | [Anything can be pinned; a pin plays at once, from the app or the home screen](0028-pins-and-home-screen-shortcuts.md) | Accepted |
| 29 | [An out-of-date build says so on every tab](0029-an-out-of-date-build-says-so-on-every-tab.md) | Accepted |
| 30 | [Shuffle all walks every page of a source into one queue group](0030-shuffle-all-walks-every-page-into-one-queue-group.md) | Accepted |
| 31 | [Downloads suggest groups to delete, reviewed before anything goes](0031-downloads-suggest-groups-to-delete.md) | Accepted |
| 32 | [Music favourites live on the YouTube Music account, and a change counts only once the account shows it](0032-music-favourites-live-on-the-account.md) | Accepted |
| 33 | [A range that drips is asked for again from the byte it reached](0033-a-dripping-range-is-asked-for-again.md) | Accepted |
| 34 | [The side quests look Roman and bold, with laurel gold that only they use](0034-the-side-quests-look-roman-and-bold.md) | Accepted |
| 35 | [An "Even out" boost rides the level passage by passage](0035-even-out-rides-the-level.md) | Accepted |
| 36 | [Recent actions come back as a Recently row and app-icon shortcuts](0036-recent-actions-come-back.md) | Accepted |
| 37 | [Exsurge nudges when the phone has lain flat while it is off](0037-exsurge-nudges-a-flat-phone.md) | Accepted |
| 38 | [Exsurge switched off comes back on when the next active day starts](0038-exsurge-off-means-off-for-today.md) | Superseded by 71 |
| 39 | [The floor is Android 13 (minSdk 33), so Totum runs on a Pixel 4a](0039-minsdk-33-for-the-pixel-4a.md) | Accepted |
| 40 | [The queue drags with the Reorderable library, and a group moves as one block](0040-the-queue-drags-by-real-positions.md) | Accepted |
| 41 | [Renovate runs at weekends, merges only its own green PRs, and Media3 waits for a soak](0041-renovate-runs-at-weekends-and-media3-waits-for-a-soak.md) | Accepted |
| 42 | [One tap switches between music and talk, within the queue](0042-one-tap-switches-between-music-and-talk.md) | Accepted |
| 43 | [Every state string starts with an emoji, and nothing spoken carries one](0043-every-state-string-starts-with-an-emoji.md) | Accepted |
| 44 | [Stats are an append-only event log, aggregated when the page opens](0044-stats-are-an-append-only-event-log.md) | Accepted |
| 45 | [A group asks before it downloads](0045-a-group-asks-before-it-downloads.md) | Accepted |
| 46 | [A shuffle can start each item at a random point](0046-a-shuffle-can-start-each-item-at-a-random-point.md) | Accepted |
| 47 | [Next and previous in the queue, everywhere the player is](0047-next-and-previous-everywhere.md) | Accepted |
| 48 | [The video page goes black when left alone](0048-the-video-page-goes-black-when-left-alone.md) | Accepted |
| 49 | [Signed-in TV links are served when the request is SmartTube's](0049-signed-in-tv-links-are-served.md) | Accepted |
| 50 | [Signed in, the account answers first and yt-dlp is the backup](0050-signed-in-first-yt-dlp-as-backup.md) | Accepted |
| 51 | [A polite YouTube client](0051-a-polite-youtube-client.md) | Accepted |
| 52 | [Blocked videos download with picture, through the signed-in path](0052-blocked-videos-download-with-picture.md) | Accepted |
| 53 | [Google Drive backup through the system picker, merged on restore](0053-google-drive-backup.md) | Accepted |
| 54 | [The side-quest cards sit at the top and pop down on events](0054-side-quest-cards-sit-at-the-top-and-pop-on-events.md) | Accepted |
| 55 | [Every collection tile has a long-press menu](0055-every-collection-tile-has-a-long-press-menu.md) | Accepted |
| 56 | [Player tabs, and Explore mode](0056-player-tabs-and-explore-mode.md) | Accepted |
| 57 | [Rewind on resume, and skipping each show's intro and outro](0057-rewind-on-resume-and-per-show-intro-outro.md) | Accepted |
| 58 | [A silent tail ends the item, and end cards are skipped by default](0058-a-silent-tail-ends-the-item.md) | Accepted |
| 59 | [New uploads flow into their shuffled group, or into an inbox](0059-new-uploads-flow-into-shuffled-groups-and-an-inbox.md) | Accepted |
| 60 | [A transcript tab, for videos and podcasts](0060-a-transcript-tab.md) | Accepted |
| 61 | [The UI does no work per playback tick](0061-the-ui-does-no-work-per-playback-tick.md) | Accepted |
| 62 | [The quick controls sit under the title](0062-the-quick-controls-sit-under-the-title.md) | Accepted |
| 63 | [The queue says whose each run is, and which group is playing](0063-the-queue-says-whose-each-run-is.md) | Accepted |
| 64 | [Auto-play stays in music or in talk](0064-auto-play-stays-in-music-or-talk.md) | Accepted |
| 65 | [The player says everything about the item](0065-the-player-says-everything-about-the-item.md) | Accepted |
| 66 | [One sort and filter menu everywhere; a queue sort changes the play order](0066-one-sort-and-filter-menu-everywhere.md) | Accepted |
| 67 | [The player's title has the whole width; the menu and Cast are in the top bar](0067-the-title-has-the-whole-width.md) | Accepted |
| 68 | [Casting plays a stream the phone serves, with Totum's own audio and the original video packets](0068-cast-a-stream-the-phone-serves.md) | Accepted |
| 69 | [Live streams play a manifest Totum builds from the live head](0069-live-streams-play-a-manifest-totum-builds.md) | Accepted |
| 70 | [Listen and Watch follow the picture, not the pillar](0070-listen-and-watch-follow-the-picture-not-the-pillar.md) | Accepted |
| 71 | [Exsurge switched off stays off, and reminds once a day](0071-exsurge-off-stays-off-and-reminds-once-a-day.md) | Accepted |
| 72 | [A playback cache under every stream](0072-a-playback-cache-under-every-stream.md) | Accepted |
| 73 | [The Library is a hub, and downloads have their own page](0073-the-library-is-a-hub-and-downloads-have-their-own-page.md) | Accepted |
| 74 | [The queue passes over finished talk when it moves on](0074-the-queue-passes-over-finished-talk.md) | Accepted |
| 75 | [A locked download is kept until it is unlocked](0075-a-locked-download-is-kept-until-unlocked.md) | Accepted |
| 76 | [Kid lock pins the app to a bare full-screen video](0076-kid-lock-pins-the-app-to-a-bare-video.md) | Accepted |
| 77 | [A download asks sound or video, and which quality](0077-a-download-asks-sound-or-video-and-quality.md) | Accepted |
| 78 | [Captions are on in English, off is for one item, and a file's own tracks are offered](0078-captions-are-on-in-english-and-off-is-for-one-item.md) | Accepted |
| 79 | [A star for anything, kept here, and a Like on YouTube for YouTube items](0079-a-star-for-anything-likes-youtube-items.md) | Accepted |
| 80 | [Choose episodes to download, and an interrupted download starts again by itself](0080-choose-episodes-to-download-and-resume-interrupted-ones.md) | Accepted |
| 81 | [The PC screen is a page the phone serves, found through a Pi bookmark](0081-the-pc-screen-is-a-page-the-phone-serves.md) | Accepted |
| 82 | [Every async operation shows itself, and an artist's shuffle plays at once](0082-every-async-operation-shows-itself.md) | Accepted |
| 83 | [Every search box offers its recent searches](0083-every-search-box-offers-its-recent-searches.md) | Accepted |
| 84 | [Listen mode offers the picture when you start something that has one](0084-listen-offers-the-picture-when-you-start-it.md) | Accepted |
| 85 | [The Pi back end lives in this repo](0085-the-pi-back-end-lives-in-this-repo.md) | Accepted |
| 86 | [Torrentio is searched beside Prowlarr](0086-torrentio-beside-prowlarr.md) | Accepted |
| 87 | [MP3 in an AVI is cut into whole frames before the decoder](0087-mp3-in-an-avi-is-cut-into-whole-frames.md) | Accepted |
| 88 | [A page opened over a list keeps the list's place](0088-a-page-opened-over-a-list-keeps-its-place.md) | Accepted |
| 89 | [A torrent result without a magnet is resolved when it is chosen](0089-a-result-without-a-magnet-is-resolved-when-chosen.md) | Accepted |
| 90 | [Each device has its own home-server token, and no link carries one](0090-a-token-per-device-and-none-in-links.md) | Accepted |
| 91 | [A one-off alarm is a daily alarm with a date](0091-a-one-off-alarm-is-a-daily-alarm-with-a-date.md) | Accepted |
| 92 | [A reminder is a daily alarm that nags, and every time is one tap away](0092-a-reminder-is-a-daily-alarm-that-nags-and-every-time-is-one-tap.md) | Accepted |
| 93 | [The private area opens the PC screen, and Watch here takes over](0093-the-private-area-opens-the-pc-screen-and-watch-here-takes-over.md) | Accepted |
| 94 | [A group's dates come from the channel feed, then each video's own publish day](0094-a-groups-dates-come-from-the-feed-then-each-videos-own-day.md) | Accepted |
| 95 | [Groups say what's new, notify each new video, and live in the Library](0095-groups-say-whats-new-and-live-in-the-library.md) | Accepted |
| 96 | [A group asks the account how far each of its videos was watched](0096-a-group-asks-the-account-how-far-each-video-was-watched.md) | Accepted |
| 97 | [The subscriptions feed is the All tab's date grid, paged by its own token](0097-the-subscriptions-feed-is-the-all-tabs-date-grid.md) | Accepted |
| 98 | [A feed never moves the rows already loaded](0098-a-feed-never-moves-rows-already-loaded.md) | Accepted |
| 99 | [A quick alarm from the alarm notification, with saved presets](0099-a-quick-alarm-from-the-alarm-notification.md) | Accepted |
| 100 | [A stalled main thread logs where it was](0100-a-stalled-main-thread-logs-where-it-was.md) | Accepted |
| 101 | [Torrent results carry trust badges](0101-torrent-results-carry-trust-badges.md) | Accepted |
| 102 | [Torrent results say what you have watched, and a tap resumes](0102-torrent-results-say-what-you-have-watched.md) | Accepted |
| 103 | [A stalled torrent holds its place, and downloads retry themselves](0103-a-torrent-holds-its-place-and-downloads-retry-themselves.md) | Accepted |
| 104 | [Downloads in three sections, and a notification per running download](0104-downloads-in-three-sections-and-a-notification-per-download.md) | Accepted |
| 105 | [Audiobooks are Internet Archive items played as podcast feeds, and torrents in the audiobook category](0105-audiobooks-are-books-from-the-archive-and-torrents.md) | Accepted |
| 106 | [A torrent wears its poster, from its site or its IMDb id](0106-a-torrent-wears-its-poster.md) | Accepted |
| 107 | [A tab says what is new since you last looked at it](0107-a-tab-says-what-is-new-since-you-looked.md) | Accepted |
