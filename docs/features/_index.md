---
title: Features
kind: index
updated: 2026-11-07
---

# Features

Status of every feature. `shipped` = on `main` and verified. Detail docs exist
for the larger / in-flight ones; small shipped features are tracked by this row
alone until they need more.

| Feature | Area | Status | Detail |
|---|---|---|---|
| 📡 The Pi plays to targets: speakers, TVs and Chromecasts each with their own queue, chips and colours in the Queue tab, Send to…, the phone's skip-silence and boost run on the Pi, YouTube progress reported only for the owner's phone; plus 🎞️ Jellyfin browse and play from the Library | casting/pi | built 2026-11-07 (ADRs 152-154; tests owed, nothing run on a real TV, speaker or Chromecast) | [pi-plays-everywhere.md](pi-plays-everywhere.md) |
| 🧳 Trip mode: say when you leave and how many per group; on Wi-Fi the phone fetches the next few of every queue group as one strip task and copies what the Pi has ready through the Pi copy path | downloads | built 2026-11-07, Batch A (ADR 149; tests owed; not run on a device) | [trip-mode.md](trip-mode.md) |
| 👶📺 Kid TV: a Library tile casts a chosen pinned playlist to a chosen TV in kid mode with one tap (1x, no silence skipping, the playlist loops, the phone stays free); choices on a page | casting/kids | built 2026-11-07, Batch A (ADR 148; tests owed; not run on a TV) | [kid-tv-button.md](kid-tv-button.md) |
| A draft post and demo-video script for an AI subreddit, in `docs/drafts/` (never posted) | publishing | built 2026-11-07, Batch A (drafts only) | [subreddit-draft.md](subreddit-draft.md) |
| ❤️ Sponsor Totum: a Settings row that opens the sponsor page, drawn only when the build carries a usable https link (`totum.sponsorUrl`) | publishing | built 2026-11-07, Batch A (ADR 147; tests owed; hidden until the link is set) | [sponsor-button.md](sponsor-button.md) |
| Licence and reporting: GPL-3.0 with third-party notices; reports go to the Pi only from a phone signed in to the home server, everyone else gets 🐙 Report on GitHub (a redacted preview, copied, the new-issue form opened) | publishing | built 2026-11-07, Batch A (ADR 146; tests owed) | [licence-and-reports.md](licence-and-reports.md) |
| 🌅 The morning job: a daily 🤖 For you playlist (videos and podcasts from outside your subscriptions, picked by Haiku from what you watch, star and like, found by the app's own search) and a 🌅 digest notification (two lines on what is new in your groups, replacing the per-video group notifications while on) | ai | built 2026-11-07 (ADR 143; tests owed, not seen on a device) | [morning-job.md](morning-job.md) |
| ✨ TL;DR, chapters and ask about this video: a three-line summary with tap-to-jump chapters and "what did they say about X?" with moments to seek to, in the Transcript tab, kept per item | ai/player | built 2026-11-07 (ADR 142; tests owed, not seen on a device) | [transcript-digest.md](transcript-digest.md) |
| 🙈 Spoiler shield: for channels you choose, titles rewritten without the result (names in a fixed order), pictures blurred, lengths hidden until watched, through one title seam every surface reads | ai/video | built 2026-11-07 (ADR 141; tests owed, not seen on a device) | [spoiler-shield.md](spoiler-shield.md) |
| Smooth video at any speed: the decoder and the screen are told each video's frame rate (worked out from the frames when the file gives none), and the screen runs at the rate that shows them evenly | playback | ✅ shipped 2026-11-06 (ADR 139) | [video-frame-rate.md](video-frame-rate.md) |
| Live streams: play from the live head at 1x; one that has not started says "🔴 Starts soon", is waited for politely (progress strip, Cancel) and calls you when live; the 🔴 LIVE / ⏺️ WAS LIVE tag is the same everywhere and kept true | playback | built 2026-11-06 (ADR 69 amended, ADR 132; tests owed) | [live-streams.md](live-streams.md) |
| 🎥 Totum recorder: screen, voice, face bubble and shown taps from an icon, saved to Movies/Totum with Share and Open | app | built 2026-11-06 (ADR 135; tests owed, not run on a device) | [totum-recorder.md](totum-recorder.md) |
| Live streams: play from the live head at 1x; one that has not started says "🔴 Starts soon", is waited for politely (progress strip, Cancel) and calls you when live | playback | built 2026-11-06 (ADR 69 amended; tests owed) | [live-streams.md](live-streams.md) |
| Casting with Totum's processing (DASH from the phone: original video packets, processed audio) | playback | prototype 2026-10-21 — YouTube video + boost on the LG and Chromecast Ultra, skip silences for talk, speed, TV seek/next, queue autoplay, stop/restart (ADR 68) | [casting.md](casting.md) |
| Stats (listens, side quests, app use) | library | built 2026-10-14 — Library → 📊 Stats from an event log, with drill-downs (ADR 44) | [stats.md](stats.md) |
| Downloads clean-up suggestions | downloads | built 2026-10-13 — four groups, review then delete, dequeues what it deletes | [downloads-cleanup.md](downloads-cleanup.md) |
| Shuffle all (channel, podcast, group) | queue/channel | shipped — every page, Shorts asked, one queue group; 🔀 on every group chip (2026-10-14) | [shuffle-all.md](shuffle-all.md) |
| Google Drive backup | backup | built 2026-10-15 — a Drive folder or file via the picker, latest + 7 dailies, on every change, merge on restore, launch prompt | [drive-backup.md](drive-backup.md) |
| A polite YouTube client | network/playback | built 2026-10-15 — one gate (4 in flight, 2/s), 429 and bot back-off, banner, `polite.*` in reports | [polite-youtube-client.md](polite-youtube-client.md) |
| Out-of-date banner | ui/diagnostics | built 2026-10-12 — red banner on every tab when a newer release exists; 2026-11-06 it downloads and installs the APK (checked first) and the first launch after an update shows What's new (ADR 130); reports carry `update.*` | [out-of-date-banner.md](out-of-date-banner.md) |
| Unified media model + playback (one controller, mini/full player) | playback | shipped | — |
| Phone rotation opens video fullscreen; portrait restores the player page | player | shipped | [player-redesign.md](player-redesign.md) |
| Offline shared links recover their titles in queue and downloads | queue/downloads | shipped | [shared-link-metadata.md](shared-link-metadata.md) |
| Podcasts: subscribe, RSS parse, episodes, refresh | podcasts | shipped | — |
| Menus go where they say; every source (show / channel) has its picture | ui | shipped | [menus-and-source-pages.md](menus-and-source-pages.md) |
| Every subscription in one list (Library), newest upload first; rows tinted by pillar | subscriptions | shipped | [all-subscriptions.md](all-subscriptions.md) |
| Every row says whether you follow its channel / show (✅ subscribed, ➕ not subscribed, ❔ unknown); tap to subscribe, or to unsubscribe after a question | subscriptions | shipped | [ADR 22](../adr/0022-every-row-says-whether-you-follow-its-source.md) |
| Fuzzy filter on every list (typos, abbreviations, accents) | ui | shipped | [list-filter.md](list-filter.md) |
| Sort and filter every list from one sheet; queue sorts change the play order with Undo | ui | shipped 2026-10-16 | [sort-and-filter.md](sort-and-filter.md) |
| Player: quick strip under the title (Listen/Watch, speed, boost, skip silences, sleep) | player | shipped 2026-10-16 | [adr 62](../adr/0062-the-quick-controls-sit-under-the-title.md) |
| Listen offers the picture: starting something with a picture while listening shows 🎬 Watch / 🎧 Keep listening over the mini player for 8 s | player | shipped 2026-10-23 | [adr 84](../adr/0084-listen-offers-the-picture-when-you-start-it.md) |
| Player: ℹ️ About this card with everything known about the item | player | shipped 2026-10-16 | [adr 65](../adr/0065-the-player-says-everything-about-the-item.md) |
| Queue: ➕ Added by you heading, ▶️ playing group header, auto-play stays in music or talk | queue | shipped 2026-10-16 | [adr 63](../adr/0063-the-queue-says-whose-each-run-is.md), [adr 64](../adr/0064-auto-play-stays-in-music-or-talk.md) |
| Multi-select on every list (long-press; bulk queue, playlist, download, played, delete, unsubscribe) | ui | shipped | [multi-select.md](multi-select.md) |
| A show's name AND its publisher, on every surface | podcasts | shipped | [show-and-publisher.md](show-and-publisher.md) |
| Videos: signed-in feeds (Home/Subscriptions/Watch Later/History) | video | shipped | — |
| Videos hidden until asked (dopamine fast): a Show videos button, hidden again on leaving the tab; setting on by default | video | shipped | [ADR 19](../adr/0019-videos-hidden-until-asked.md) |
| YouTube TV device-code OAuth | auth | shipped | — |
| Unified search (iTunes + InnerTube search, yt-dlp fallback → `SearchHit`) | search | shipped | — |
| Search history (recent queries in every search box: main, list filters, channel, transcript) | search | shipped | [search-history.md](search-history.md) |
| Torrents: public-domain film & TV via the home server | torrent | shipped | [torrents.md](torrents.md) |
| ✨ Ask: say what you want, Haiku picks, the app finds the copies, you confirm; or ask about your own queue, history, downloads and stats | search | refining | [ask.md](ask.md) |
| 🏠 Downloads at home: the Pi downloads anything, plays from home, copies to the phone on Wi-Fi | downloads | refining | [downloads-at-home.md](downloads-at-home.md) |
| Audiobooks: free books (LibriVox, Internet Archive) and audiobook torrents, chapters in order with resume | search | shipped | [audiobooks.md](audiobooks.md) |
| [Daily alarms](daily-alarms.md) — asked each weekday morning, rung full screen (pickup 17:30 first) | side-quest | built 2026-10-07, ships off | — |
| 🔕 Work apps quiet hours: groups of apps (Work: Slack, Outlook, Teams; others) each snoozed outside their own hours, one status line on the alarm board, a notification at each change | side-quest | built 2026-11-06 (groups and change notifications too), ships off, tests owed | [work-apps-quiet-hours.md](work-apps-quiet-hours.md) |
| 🌙 Bedtime sleep timer: the sleep timer arms itself at night (stop after the current item), and every sleep timer fades out | player | built 2026-11-06, ships off, tests owed (ADR 133) | [bedtime-sleep-timer.md](bedtime-sleep-timer.md) |
| Downloads (video merge, sponsors skipped live from the latest list (ADR 16) / podcast enclosure) | downloads | shipped | — |
| Comments, related, like/dislike, Watch Later, subscribe | video | shipped | — |
| Playlists (account) | video | shipped | — |
| Channel page (subscribe + uploads) | channel | shipped | — |
| Chapters (yt-dlp + Podcasting 2.0 `psc`/remote) + seek-bar markers | playback | shipped | — |
| Transcript tab: follows playback, tap a line to seek, search (YouTube captions, podcast:transcript) | player | shipped | [adr 60](../adr/0060-a-transcript-tab.md) |
| Radio from several songs (multi-select → 📻 Radio from these) | music | shipped | [adr 27](../adr/0027-radio-repeat-and-shuffle-are-queue-features.md) |
| New uploads into shuffled groups and a 📥 New inbox (every 2 h on Wi-Fi, or ⋮ → Pull new uploads) | queue | shipped | [adr 59](../adr/0059-new-uploads-flow-into-shuffled-groups-and-an-inbox.md) |
| Silent tail ends the item; SponsorBlock end cards skipped by default | playback | shipped | [adr 58](../adr/0058-a-silent-tail-ends-the-item.md) |
| Rewind on resume (2–20 s by pause length, never music) and each show's intro/outro skipped | playback | shipped | [adr 57](../adr/0057-rewind-on-resume-and-per-show-intro-outro.md) |
| Playback queue (unified up-next; play a row in place of the current one; mode switches retain item and position) | playback | shipped; mode repairs in 0.1.603 | [playback-queue.md](playback-queue.md) |
| Streaming reliability (chunked fetch, hardware-aware codec, expired-URL recovery, SABR's four false endings) | playback | shipped | [streaming-reliability.md](streaming-reliability.md) |
| Permanent vs transient failure, and playback that goes nowhere (ended / failed / stalled / never asked) | playback | shipped | [failure-handling.md](failure-handling.md) |
| Diagnostics: nav / place / view-model / queue-intent trails | diagnostics | shipped | — |
| Tabs remember where you were (per-destination saved state) | ui | shipped | — |
| Shorts reel (full-screen vertical pager) | video | shipped | — |
| Skip-silence: pauses over 150ms cut to 40ms, podcasts and video, picture follows the sound; Standard and Smart (speech-detecting) modes with a test player in Settings | playback | shipped | [skip-silence.md](skip-silence.md) |
| Sleep timer (every timer fades out over its last 20 s; Bedtime arms it at night) | playback | shipped | [bedtime-sleep-timer.md](bedtime-sleep-timer.md) |
| Per-source playback-speed memory | playback | shipped | — |
| New-content notifications (background refresh, both pillars; per-kind and per-group switches) | notifications | shipped | [new-content-notifications.md](new-content-notifications.md) |
| Import / export subscriptions (OPML / NewPipe / Takeout) | subscriptions | shipped | — |
| Local cross-pillar playlists (mix podcasts + videos) | library | shipped | [../todos/local-cross-pillar-playlists.md](../todos/local-cross-pillar-playlists.md) |
| Play history (recently played, both pillars) | library | shipped | [play-history.md](play-history.md) |
| Source pages (channel page + podcast feed page, shared `SourceHeader`) | channel/podcasts | shipped | — |
| Go to channel / podcast from any row (`SourceLocator`) | ui | shipped | [../todos/go-to-channel-action.md](../todos/go-to-channel-action.md) |
| Cast to TV (Chromecast) — best-effort | cast | shipped* | podcast/local only works; video casting fragile |
| Explore channel content (InnerTube tabs: Videos/Shorts/Playlists) | channel | shipped | [channel-browse.md](channel-browse.md) |
| One visual language across the app | ui | shipped | [visual-language.md](visual-language.md) |
| The player screen, tinted by what is playing | player | shipped | [player-redesign.md](player-redesign.md) |
| Managing downloads (cancel, retry, sort by size, see failures) | downloads | shipped | [download-management.md](download-management.md) |
| Search results arrive per section (no longer blocked by torrent search) | search | shipped | [search-sections.md](search-sections.md) |
| Search results as tabs (Videos, Torrents, Audiobooks, Podcasts, Songs, Albums and artists) with counts and a still-searching dot | search | built 2026-11-06 | [search-sections.md](search-sections.md) |
| Views and dates everywhere (every list, and the video page) | video/search/playback | shipped | [upload-dates.md](upload-dates.md) |
| Crash + diagnostics reporting (verbose reports to the Pi) | infrastructure | shipped | [crash-reporting.md](crash-reporting.md) |
| Row status (pillar / played / offline / played wash on every row) | ui | shipped | [row-status.md](row-status.md) |
| Text wraps — nothing ends in three dots | ui | shipped | [text-wraps-never-truncates.md](text-wraps-never-truncates.md) |
| Feed pagination (account feeds + channel tabs) | video | shipped | [feed-pagination.md](feed-pagination.md) |
| Shorts and live streams listed with the videos, tagged | video | shipped | [shorts-in-the-feed.md](shorts-in-the-feed.md) |
| Two-way progress sync with YouTube (resume across devices) | video | shipped | [progress-sync.md](progress-sync.md) |
| Brand: Totum name, palette and icon | branding | shipped | [brand.md](brand.md) |
| Download notifications (progress / done / failed, aggregated) | downloads | shipped | [download-notifications.md](download-notifications.md) |
| Quality + speed as an on-video overlay | ui | shipped | [video-settings-overlay.md](video-settings-overlay.md) |
| Subtitles / captions (menu on the video, cues over the picture) | playback | shipped | [subtitles.md](subtitles.md) |
| Audio tracks: the right language by default, and a menu to change it | playback | shipped | [audio-tracks.md](audio-tracks.md) |
| Periodic jobs: one `PeriodicJob` helper and a persisted last run ("✅ checked 2 days ago") | platform | built 2026-11-06 (tests owed; ADR 129) | [periodic-jobs.md](periodic-jobs.md) |
| The queue tidies itself weekly (played talk older than 14 days, failed downloads) | queue | built 2026-11-06 (tests owed) | [queue-tidy-up.md](queue-tidy-up.md) |
| Backups are test-read weekly ("✅ Backup checked 2 days ago") | backup | built 2026-11-06 (tests owed) | [backup-checked.md](backup-checked.md) |
| A daily mobile-data limit (video plays as sound only past it; 📶 chip) | playback | built 2026-11-06 (tests owed; counter not yet seen on a phone) | [mobile-data-budget.md](mobile-data-budget.md) |
| Search in Settings (fuzzy, jumps to the row or opens its page) | settings | built 2026-11-06 | [settings-search.md](settings-search.md) |
| Share and copy link on every item menu; a group shares its list of titles | app | built 2026-11-06 (tests owed; ADR 134) | [share-link.md](share-link.md) |
| The queue says how many items play with no signal (✈️ chip) and filters to them | queue | built 2026-11-06 (tests owed; ADR 136) | [offline-playable-chip.md](offline-playable-chip.md) |
| A ▶️ Carry on row tops the Library: half-watched items, newest first | library | built 2026-11-06 (tests owed; ADR 137) | [carry-on-row.md](carry-on-row.md) |
| Each queue group says how long is left (⏳ 4 h 12 min left at 2x, or at least) | queue | built 2026-11-06 (tests owed; ADR 138) | [group-time-left.md](group-time-left.md) |
| The mini player shows every mode that is on (🔂 🔁 🔀 🧭 ⏩ 👶 🔕 ⏰); the notification shows 🔂 | player | built 2026-11-06 (tests to follow) | [active-modes.md](active-modes.md) |
| The playback notification and lock screen say "⏭️ Next: …" (from the queue, refreshed on every queue edit; 🔂 replaces it under repeat one) | player | built 2026-11-06 (tests to follow; device check owed; ADR 131) | [next-in-notification.md](next-in-notification.md) |
| Tap the title (or long-press the mini player) to see why this is playing: tapped, next in the group, Explore, repeat, radio, new upload … | player | built 2026-11-06 (tests to follow; emulator look owed; ADR 131) | [why-is-this-playing.md](why-is-this-playing.md) |
| A group page shows its last list while refreshing (kept on disk) | video | built 2026-11-06 (tests to follow) | [group-page.md](group-page.md) |
| Settings that stay put across the next video (speed, boost, captions, quality, track) | playback | shipped | [settings-that-stay-put.md](settings-that-stay-put.md) |
| Command line: `totum "jazz live stream"` on Linux/macOS, same libraries as the app | cli | shipped | [command-line.md](command-line.md) |
| YouTube Music: a Music tab (songs, albums, artists), album and artist pages, endless radio, your YouTube Music library, shared album links; music plays as sound | music | shipped | [youtube-music.md](youtube-music.md) |
| Search is a 🔍 in every tab header, opening over the tab | search | shipped | [ADR 24](../adr/0024-music-tab-and-search-in-every-header.md) |
| Shortcuts: pin anything (both pillars), a Pinned row on Music and Library, home-screen icons; a pin plays at once | library | shipped | [shortcuts.md](shortcuts.md) |
| Repeat (off / queue / this one) and shuffle up next, for both pillars | queue | shipped | [ADR 27](../adr/0027-radio-repeat-and-shuffle-are-queue-features.md) |
| Picture-in-Picture (video keeps playing when you leave) | playback | shipped | [../todos/feature-gap-review.md](../todos/feature-gap-review.md) |
| Offline library across both pillars (downloads carry their item) | downloads | shipped | [../todos/library-downloads-podcast-only.md](../todos/library-downloads-podcast-only.md) |
| Loading feedback (global busy bar; "go to channel" 12.5s → 59ms) | ui | shipped | [loading-feedback.md](loading-feedback.md) |
| Feed cache (the Videos tab opens with content, not a blank) | video | shipped | [feed-cache.md](feed-cache.md) |
| Offline queue (audio fetched automatically, readiness in words) | downloads | shipped | [offline-queue.md](offline-queue.md) |
| Quiet podcasts made audible, automatically (measures the item, cannot clip) | playback | shipped | [loudness-boost.md](loudness-boost.md) |
| Exsurge et Disce: stand up every 30 min sitting, Loquax or a walking break with Totum playing, Surgius and an always-present banner | side-quest | built, awaiting on-phone verification | [exsurge-et-disce.md](exsurge-et-disce.md) |

\* Cast: **tapping the button still crashed the app until 2026-07-28** — this footnote claimed
otherwise for weeks, because the fix that was made (a themed context for the button) never
covered the picker dialog, and nobody tapped it again to find out. Two crashes were stacked:
`IllegalStateException` (the picker is a DialogFragment, so the activity must be a
`FragmentActivity`) and, once past that, `IllegalArgumentException: background can not be
translucent` (MediaRouter themes the dialog from AppCompat attributes on the **activity**).
Both fixed in `8d900c5`, verified by actually tapping it. Real casting still unverified — no
hardware. Disconnect-loses-playback fixed earlier.

See [`../todos/`](../todos/_index.md) for smaller requested items not yet features.
