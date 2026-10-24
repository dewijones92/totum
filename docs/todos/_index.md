---
title: Backlog
kind: index
updated: 2026-10-23
---

# Backlog

Requested items not yet built (or in flight). One file per item. Move a `status`
to `shipped` and migrate it to `../features/` once it's a real feature on `main`.

| Item | Area | Priority | Status |
|---|---|---|---|
| [Shuffle all across several sources at once](multi-source-shuffle.md) | queue/shuffle | high | ⏳ partly done 2026-10-22 (Subscriptions, queue groups) |
| [Exsurge off never summons; one nudge a day](exsurge-off-never-summons.md) | side-quest | high | done 2026-10-22 (ADR 71) |
| [Rewind without buffering, for everything](instant-rewind-cache.md) | playback | high | built 2026-10-22 (ADR 72: 1 GB playback cache) |
| [End talk early when the rest has no speech](end-early-no-speech.md) | playback | high | done 2026-10-23: files, and audio-only streams on Wi-Fi (ADR 58) |
| [Shuffle all on an artist duplicates the song playing](artist-shuffle-duplicate.md) | music/queue | high | done 2026-10-22 (93ac61c6: the playing song moves into the new group) |
| [Show the buffer on the seek bar](seek-bar-buffer.md) | player/ui | medium | built 2026-10-22 (strip under the slider) |
| [Repeat the current queue group](repeat-group.md) | queue | medium | done 2026-10-22 (🔁 This group in the repeat picker) |
| [Every download string says audio or video](downloads-say-audio-or-video.md) | downloads/ui | high | ⏳ partly done 2026-10-22 (every download action says what it fetches) |
| [Choose audio, video (and quality) or both when downloading](download-choice-and-quality.md) | downloads | high | done 2026-10-23 (ADR 77) |
| [Lock a video for a child; a locked Peppa Pig shortcut](kid-lock-and-shortcut.md) | player/kids | high | ✅ done 2026-10-22 (ADR 76) |
| [Skip finished items when the queue moves on](skip-finished-on-advance.md) | queue | high | ✅ done 2026-10-22 (ADR 74) |
| [Download a whole torrent series or a queue group](download-series-and-groups.md) | downloads/torrents | high | done 2026-10-23 (ADR 80) |
| [Torrent items have no Watch / Listen](torrent-watch-listen.md) | torrents/player | high | done 2026-10-22 (c7b1967a, ADR 70: Listen/Watch for anything with a picture) |
| [What the Pi stores for Totum, and what it should](pi-storage-review.md) | pi/infra | medium | 🔍 investigated 2026-10-22: Totum uses ~100 MB; the disk is the media library |
| [A ⭐ for any item](star-anything.md) | library | medium | done 2026-10-23 (ADR 79) |
| [Recent searches in every search box](recent-searches-everywhere.md) | search/ui | medium | done 2026-10-23 (ADR 83) |
| [Torrent search answers fast and never gives up early](torrent-search-speed.md) | search/torrents/pi | high | decided 2026-10-24, in progress |
| [Confirm big destructive actions](confirm-big-actions.md) | queue/ui | high | done 2026-10-22 (clear queue/history, delete downloads, remove group: hold to confirm) |
| [Lock a download so it is not easily deleted](locked-downloads.md) | downloads | high | ✅ done 2026-10-22 (ADR 75) |
| [Reset a whole group or list to the start](reset-group-progress.md) | queue/library | medium | built 2026-10-22 for queue groups (⋮ → ↩️ Start again; ☑️ Select these → Mark as unplayed for any subset) |
| [Speed through long pauses in videos while casting](video-skip-silences-speed.md) | cast | low | requested 2026-10-22 (Dewi's 24 h batch) |
| [Totum's boost on live sound while casting](live-cast-boost.md) | cast | low | requested 2026-10-22 (Dewi's 24 h batch) |
| [Triage the 16 unconsidered phone reports (15-22 Oct)](phone-reports-oct-15-22.md) | diagnostics | high | requested 2026-10-22 (Dewi's 24 h batch) |
| [The first visit to Music and Queue costs one long frame](first-tab-visit-cost.md) | performance | medium | found 2026-10-15 (ADR 61 sweep), not started |
| [A radio seeded from several songs](radio-from-several-songs.md) | music | high | built (select songs → "Radio from these", ADR 27 revision 2026-10-15); this row was stale until 2026-10-22 |
| [Make Totum run on a Pixel 4a](run-on-pixel-4a.md) | platform | high | built 2026-10-14 → ADR 39; minSdk 33, verified on totum-api33 |
| [A bold Roman redesign of the Exsurge and Daily alarms screens](side-quest-ui-redesign.md) | side-quest/ui | high | built 2026-10-14 → ADR 34; ring and question screens not yet seen ringing |
| [Artist page: Shuffle all songs, and the Shuffle station fixed](artist-shuffle-all-songs.md) | music | high | built 2026-10-14 → ADR 30 amended |
| [A stats page with drill-downs](stats-page.md) | library | high | built 2026-10-14 → ADR 44 (log and page, this batch) |
| [Queue groups move and shuffle like items](queue-groups-move-and-shuffle.md) | queue | high | built 2026-10-14 → ADR 40, with the drag rewrite |
| [Surgius cries when Exsurge is off or paused](surgius-cries-when-off.md) | side-quest | medium | built 2026-10-14 → exsurge-et-disce feature doc |
| [One tap to switch between music and talk](music-talk-toggle.md) | queue/playback | high | built 2026-10-14 → ADR 42 |
| [Exsurge nudges when the phone has lain flat while it is off](exsurge-flat-phone-nudge.md) | side-quest | high | built 2026-10-14 → ADRs 37, 38 |
| [An "Even out" loudness mode](even-out-loudness.md) | playback | high | built 2026-10-14 → ADR 35 |
| [Recent actions as a Recently row and app shortcuts](recently-row-and-shortcuts.md) | queue/ui | medium | built 2026-10-14 → ADR 36 |
| [Suggest groups of downloads to delete](suggest-downloads-to-delete.md) | downloads | high | built 2026-10-13 → [features/downloads-cleanup.md](../features/downloads-cleanup.md), ADR 31 |
| [Set today's alarm from the alarms page](alarm-on-from-the-page.md) | side-quest | medium | built 2026-10-12 → [features/daily-alarms.md](../features/daily-alarms.md) |
| [Shuffle everything a channel or podcast has published](shuffle-a-whole-source.md) | queue/channel | medium | built 2026-10-12 → [features/shuffle-all.md](../features/shuffle-all.md) |
| [Music favourites saved to the YouTube Music account](account-backed-music-favourites.md) | music/account | medium | built 2026-10-14 → [features/youtube-music.md](../features/youtube-music.md), ADR 32 |
| [Playback findings from the 0.1.601 phone reports](playback-defects-0.1.601.md) | playback/downloads | high | four repairs and the local-podcast picture correction released in 0.1.603; APK verified and server findings #37–41 fixed; attempt 602 withdrawn; live CI remains red on streaming failures; boost discussion and buffering cause remain open |
| [Intelligent search ("the best soothing flamenco album")](ai-search.md) | search | — | to discuss with Dewi |
| [Search's video hits use the watch URL as their id; everything else uses the video id](search-video-hit-ids.md) | search | medium | done — fixed in 94463e75 (DB v26); the file said open until 2026-10-14 |
| [A premium ("triple-A") look and better recommendations](premium-ui-and-recommendations.md) | ui | — | to discuss with Dewi: what "triple A" means, which screens, what recommendations draw on |
| [Cut more sponsor segments with SponsorBlock](more-sponsorblock-cuts.md) | playback/downloads | medium | built 2026-10-07 (ADR 16): downloads keep segments, files on disk skip live from the stored list; old cut files left alone |
| [Switching audio to video and opening a channel feel slow](slow-switch-and-channel.md) | playback/channel | high | phone: audio copy → video 16.7 s, 85% extraction; fixes shipped (ADRs 11, 12): solver cache, expiry-aware resolve cache kept across restarts, look-ahead, V8 solver, shared HTTP pool, caption translations limited, account calls off the critical path; to measure on the phone |
| [The "ask YouTube directly" fallback serves TV URLs that 403](tv-direct-ask-urls-403.md) | playback | medium | cause fixed 2026-10-15 (ADR 49): the TV-scale timestamp, not a missing PO token (the 2026-10-08 diagnosis was wrong); the ladder is still checked before it replaces yt-dlp's stream |
| [Queue items seem to finish a few seconds early](queue-items-feel-early.md) | playback | medium | closed 2026-10-08: phone reports show skip-silence cutting ~1.1 s of trailing quiet; old sponsor-cut downloads end minutes early; Dewi chose to leave both |
| [Seamless playback across TV, laptop, browser and other devices](seamless-cross-device-playback.md) | playback/integration | high | parked 2026-10-08 until Dewi picks it up; priority — "cast, in the broad sense, to my TV" (Fire TV Stick; Chromecast casting only best-effort today) |
| [seek-before-account-resume](seek-before-account-resume.md) | playback | high | fixed 2026-10-05: a seek overrides the cached account figure before its first resume |
| [shared-title-stays-placeholder](shared-title-stays-placeholder.md) | queue/downloads | high | fixed 2026-10-05 → [shared-link metadata](../features/shared-link-metadata.md) |
| [audio-download-recorded-as-full](audio-download-recorded-as-full.md) | downloads | high | fixed 2026-10-01 for new downloads: the signed-in fallback recorded audio as a full copy, so VIDEO mode played no picture; downloads already mislabelled need re-downloading |
| [playback-service-foreground-start-refused](playback-service-foreground-start-refused.md) | playback | high | fixed 2026-10-01: Media3's late notification callback is wrapped, so a refused foreground start is logged rather than crashing (also the cause of a missed autoplay) |
| [stall-rescue-overrides-a-pause](stall-rescue-overrides-a-pause.md) | playback | medium | fixed 2026-10-02 (the 2026-10-01 fix was ineffective on real Media3): a pause during a rescue now holds |
| [exsurge-et-disce](exsurge-et-disce.md) | side-quest | medium | built 2026-10-01 → [features/exsurge-et-disce.md](../features/exsurge-et-disce.md); awaiting a real day on the phone |
| [exsurge-back-goes-to-totum](exsurge-back-goes-to-totum.md) | side-quest | low | built 2026-10-07: Back opens the player if an item is loaded, else Totum |
| [exsurge-go-scrolls-to-current-lesson](exsurge-go-scrolls-to-current-lesson.md) | side-quest | medium | built 2026-10-07: GO opens Loquax on Learn, scrolled to the current lesson and highlighted (Loquax ADR 19) |
| [exsurge-continue-video-test-fails-on-emulator](exsurge-continue-video-test-fails-on-emulator.md) | testing | low | corrected 2026-10-07: state-dependent, passed in two full runs later that day; cause not established |
| [Music favourites polish — a cover for Liked songs, and Like on album track rows](music-favourites-polish.md) | music | low | done 2026-10-14; player Like merge open |
| [MultiSelectTest fails locally; the long-press never reaches the row](multiselect-test-fails-locally.md) | testing | medium | open — refined 2026-10-14; leads: a clickable child (the follow mark?) under the title, geometry, merged semantics |
| [daily-alarms](daily-alarms.md) | side-quest | high | built 2026-10-07 → [features/daily-alarms](../features/daily-alarms.md); ships off, awaiting Dewi's first weekday |
| [exsurge-ideas](exsurge-ideas.md) | side-quest | low | proposed: 24 ideas waiting on keep / cut / later |
| [youtube-requires-attestation](youtube-requires-attestation.md) | playback | high | the ~1MB cap was the ANDROID/WEB endpoint, not attestation: SABR now resolves from the embedded player and streamed 4.2MB+ on device with no token (shipped 2026-09-07); the signed-in TV client is no longer refused (timestamp scale). Still open: quality tiers above what the embedded response offers, and seeking. Ordinary streaming WORKS via the yt-dlp fallback routes — the earlier "nothing un-downloaded streams" here was stale (corrected 2026-09-06; 1080p streamed on the emulator that morning) |
| [sabr-serves-nothing-for-some-videos](sabr-serves-nothing-for-some-videos.md) | playback | — | **reopened for a re-check 2026-10-14**: the same "SABR served nothing" failure is back on CI (3 of the last 8 red runs); closed 2026-09-25 as the ANDROID endpoint's ~60 s wall |
| [subtitles-fail-to-parse-over-sabr](subtitles-fail-to-parse-over-sabr.md) | playback | low | FIXED and confirmed in run 35529171153: asks=vtt, zero parse failures, and the loads verified to have actually run |
| [instrumented-tests-read-the-wrong-item](instrumented-tests-read-the-wrong-item.md) | testing | medium | done 2026-10-08: all fourteen converted tests pass on totum-api35; CI's live phase had been skipping (stale home-IP secret), now follows the home domain |
| [YouTube refuses subtitles with 429; the app hammered it and said nothing](subtitles-refused-429.md) | playback | high | back-off shipped 2026-10-14; on-screen message and a second caption source open |
| [The learned fresh-URL window aims at the earliest acceptance](young-url-window-aims-too-early.md) | playback | medium | open — 4–6 young 403s per play on the emulator; edon5wb5Qsc give-up rare; needs phone data before changing |
| [an-hour-long-item-stalls-early-in-ci](an-hour-long-item-stalls-early-in-ci.md) | playback | high | cause found 2026-10-14: one request opened at play start drips (280 KB in 21.9 s); re-ask fix committed (ADR 33), unpushed; the next CI run is the proof |
| [stalls-near-the-end-of-an-item](stalls-near-the-end-of-an-item.md) | playback | high | not seen since 0.1.477 (10 phone reports, mostly local files); cause not established; its detector cried wolf on every seek and is fixed |
| [Explore channel content](../features/channel-browse.md) | channel | high | shipped |
| [Views and dates everywhere](../features/upload-dates.md) | video/search/playback | high | shipped (incl. the video page, 2026-08-06) |
| [background-audio-listen-mode](background-audio-listen-mode.md) | playback | high | shipped |
| [fullscreen-video-stretch](fullscreen-video-stretch.md) | playback | high | shipped |
| [long-press-context-menu](long-press-context-menu.md) | ui | medium | shipped (all feeds; go-to-channel split out) |
| [go-to-channel-action](go-to-channel-action.md) | ui | medium | shipped |
| [url-share-target](url-share-target.md) | integration | medium | shipped |
| [play-history-screen](play-history-screen.md) | library | medium | shipped |
| [local-cross-pillar-playlists](local-cross-pillar-playlists.md) | library | high | shipped |
| [skip-silence-on-video](skip-silence-on-video.md) | playback | medium | superseded — video cuts samples, picture follows the sound |
| [queue-first-playback](queue-first-playback.md) | playback | high | shipped (incl. drag-reorder) |
| [auto-download-queue](auto-download-queue.md) | downloads | high | shipped |
| [autoplay-next-guaranteed](autoplay-next-guaranteed.md) | playback | medium | shipped |
| [volume-boost-normalize](volume-boost-normalize.md) | playback | medium | shipped |
| [subtitles-captions](subtitles-captions.md) | playback | medium | shipped (video; podcast transcripts open) |
| [youtube-progress-two-way-sync](youtube-progress-two-way-sync.md) | video | medium | **shipped** → [features/progress-sync.md](../features/progress-sync.md) |
| [ui-polish](ui-polish.md) | ui | medium | quality/speed overlay shipped; wider sweep open |
| [rebrand](rebrand.md) | branding | medium | shipped as **Totum** |
| [crash-reporting](crash-reporting.md) | infrastructure | high | shipped (verbose reports live on the Pi) |
| [row-status-indicators](row-status-indicators.md) | ui | high | shipped (real PlayState behind it) |
| [high-quality-playback-fix](high-quality-playback-fix.md) | video | high | shipped |
| [feature-gap-review](feature-gap-review.md) | planning | — | triage of the AI review |
| [channel-groups](channel-groups.md) | video | high | shipped |
| [watch-history-not-recorded](watch-history-not-recorded.md) | video | high | done — fixed by an authenticated player call carrying a current signatureTimestamp |
| [sabr-streaming](sabr-streaming.md) | video | medium | fallback + QuickJS shipped; the ~1MB wall fell 2026-09-07 (embedded endpoint); seeking still open |
| [feed-pagination](feed-pagination.md) | video | high | shipped — feeds, channel tabs AND search; playlist/related still page one |
| [queue-drag-reorder](queue-drag-reorder.md) | queue | high | shipped (auto-scroll); pickup + resilience open |
| [public-domain-film-tv](public-domain-film-tv.md) | search | medium | **shipped** → [features/torrents.md](../features/torrents.md) |
| [age-restricted-videos](age-restricted-videos.md) | video | high | shipped — age-restricted videos play on-device |
| [torrent-zero-config](torrent-zero-config.md) | torrent | high | **shipped** — host from a CI secret, sign-in one tap |
| [testing-depth](testing-depth.md) | tests | medium | refining |
| [audio-video-switching](audio-video-switching.md) | playback | high | shipped (local-audio merge outstanding) |
| [notification-opens-app](notification-opens-app.md) | playback | high | shipped |
| [listen-mode-exit-ux](listen-mode-exit-ux.md) | playback | high | shipped |
| [library-downloads-podcast-only](library-downloads-podcast-only.md) | downloads | medium | done |
| [playback-does-not-resume-after-network-loss](playback-does-not-resume-after-network-loss.md) | playback | high | done — StreamRecovery waits for a validated network, then resumes |
| [download-races-playback](download-races-playback.md) | playback | high | done — downloads now yield to playback; the duplicate extraction itself remains |
| [listen-mode-saves-data](listen-mode-saves-data.md) | playback | medium | done — YouTube always did; torrents stream audio-only via the Pi |
| [downloaded-video-not-played-offline](downloaded-video-not-played-offline.md) | playback | high | fixed — one routing decision for both pillars |
| [members-only-downloads](members-only-downloads.md) | downloads | high | shipped — yt-dlp falls back to the app's own signed-in fetch |
| [torrents-through-the-unified-route](torrents-through-the-unified-route.md) | torrent | medium | answered + fixed; films no longer auto-fetched |
| [metered-audio-switch](metered-audio-switch.md) | playback | high | shipped — proven with the radios toggled |
| [prefetch-the-next-item](prefetch-the-next-item.md) | playback | medium | done — readiness and byte preload, Wi-Fi only |
| [skip-silence-smoothness](skip-silence-smoothness.md) | playback | high | superseded — it never cut anything; see features/skip-silence.md |
| [offline-queue-e2e](offline-queue-e2e.md) | tests | high | done — in CI, and it found a real bug |
| [buffering-defects-0.1.332](buffering-defects-0.1.332.md) | playback | high | all four fixed with tests |
| [settings-only-change-when-asked](settings-only-change-when-asked.md) | settings | high | done — speed, boost and brightness hold, incl. across a fullscreen toggle (fixed 2026-08-08) |
| [buffer-ahead-gauge](buffer-ahead-gauge.md) | playback | medium | shipped — seconds-ahead gauge on the scrub bar; flicker fixed 2026-09-26 |
| [tab-state-preservation](tab-state-preservation.md) | navigation | high | shipped |
| [diagnostics-triage-state](diagnostics-triage-state.md) | diagnostics | high | shipped |
| [a-recovery-resumes-the-next-item-at-the-last-items-position](a-recovery-resumes-the-next-item-at-the-last-items-position.md) | playback | high | fixed 2026-09-06 — StreamRecovery drops stale failures |
| [outbound-progress-sync-is-dead](outbound-progress-sync-is-dead.md) | video | **critical** | **fixed 2026-09-20** — not a regression: three untrackable videos at the head of the outbox blocked 120 sendable ones, every pass |
| [the-next-video-does-not-play-offline](the-next-video-does-not-play-offline.md) | playback | high | fixed 2026-09-06 — resume no longer waits on the account offline |
| [a-share-replays-after-the-process-is-killed](a-share-replays-after-the-process-is-killed.md) | share | high | fixed 2026-09-23 — decided by how Android delivered the intent (Recents flag / saved state); the 0.1.346 mark never reached the task's copy |
| [a-shared-link-that-cannot-resolve-vanishes](a-shared-link-that-cannot-resolve-vanishes.md) | share | medium | fixed 2026-09-06 — queued by its id, resolved when it plays; the row now learns its title and date on resolution |
| [queue-rows-without-a-date](queue-rows-without-a-date.md) | ui/queue | medium | fixed 2026-09-06 — anchored instants rendered against a ticking clock; shared rows learn their facts |

All backlog items are Dewi requests. `refining` = spec written, decisions still open;
`ready` = decisions made, implementation waits for Dewi's explicit go (his standing
instruction on the queue/download/autoplay/volume group: refine first, implement on
command).
- [The signed-in TV client's /player is refused](tv-client-player-is-refused.md) — **fixed 2026-09-06**: the TV client wants `signatureTimestamp` 20697001, not the script's 20697; found by capturing SmartTube on the emulator
- [SABR cannot play a live stream without its initialization segment](sabr-live-needs-an-init-segment.md) — SABR fetches live fine (820KB kept in one fetch) and the player never becomes ready; the init data is on the wire in a part we discard
- [SABR is capped at ~1MB per format](sabr-stops-at-one-megabyte.md) — **ANSWERED 2026-09-07**: the cap belonged to the ANDROID/WEB endpoint; sessions now come from the embedded player and stream past it on device
- [A resend is read as an empty answer and answered with a 30-second skip](a-resend-becomes-a-thirty-second-skip.md) — the response carried 171364B of this format's own bytes; `carried` already knows, and `handleEmpty` does not ask
- [Move SABR onto a Media3 ChunkSource](sabr-as-a-chunk-source.md) — designed. The proper fix for seeking AND the only route to adaptive quality, which is structurally unreachable under a progressive source (proven against the media3 1.10.1 jars)
- [SABR cannot be opened part-way through](sabr-cannot-seek.md) — a cold jump IS served on the embedded endpoint (2026-09-07, an hour in, 500KB a fetch); what is left is ours: a byte-addressed reader cannot consume a time-addressed answer — the ChunkSource redesign
- [The signed-in TV /player answers UNPLAYABLE](signed-in-player-is-unplayable.md) — **fixed 2026-09-06**, it was the stamp's scale; age-restricted fallback re-measured working
| [Upgrading a download keeps the old copy until the new one lands](download-upgrade-keeps-the-old-copy.md) | downloads | medium | ✅ done 2026-10-23 |
| [The preloader's held copy should carry the next item's caption files](preload-keeps-captions.md) | playback | low | ✅ done 2026-10-23 (ADR 78) |
| ["Now playing" drifted after quick skips](now-playing-drifts-after-quick-skips.md) | queue | high | done 2026-10-23 (QueueSkips) |
| [Watch from the phone on the PC display](pc-display-receiver.md) | casting | high | ✅ built 2026-10-23 (ADR 81): a page the phone serves, found through the Pi bookmark |
| [A live strip on every tab while something long runs](progress-strip-for-long-operations.md) | ui | high | ⏳ seam + strip built 2026-10-23 (ADR 82); pages, torrents, batches to move on |
| [ExsurgeTakeoverFlowTest's paused-podcast case fails now and then on CI](exsurge-continue-podcast-test-flaky.md) | exsurge | medium | open 2026-10-23: red on 0fb35de5 and 1a2d2c35, green 10 runs between and 3/3 locally |
| [In Listen mode, offer the picture](listen-mode-offers-the-picture.md) | playback | high | done 2026-10-23 (ADR 84) |
| [Everything Totum runs on the Pi lives in this repo](totum-backend-in-this-repo.md) | pi/infra | medium | done 2026-10-23 (ADR 85) |
