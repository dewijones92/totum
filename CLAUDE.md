# Totum

**Totum** — Latin for "the whole". One app for all of it; the name deliberately avoids
"duo"/"uni" so a third pillar wouldn't make it a lie. Named 2026-07-25 (was UniApp);
`applicationId` is `com.dewijones92.totum`.

One Android app that replaces two: **PipePipe** (YouTube-style streaming client) and
**AntennaPod** (podcast manager). Streaming and podcasts are both first-class pillars
sharing one unified domain model — subscriptions, playback queue, downloads, and history
behave identically whether the source is a video channel or an RSS feed.

The original brief lives in `init` at the repo root. Decisions below supersede it where
they conflict (notably: the `ytdlp-kt` fork dependency mentioned in `init` was dropped
in favour of a from-scratch library — see Decisions).

**Living docs:** `AGENTS.md` → `docs/` holds a maintained hierarchy (frontmatter'd
markdown) documenting features, the backlog (`docs/todos/`), tests, and architecture.
Keeping them current is part of "done": ship/change a feature → update
`docs/features/<name>.md`; start/finish/drop a backlog item → update `docs/todos/`;
change coverage → update `docs/tests/`. Bump each doc's `updated`.

**CRUD the ADRs** (Dewi, 2026-10-01: *"remember to CRUD any ADR"*). Decisions live in
`docs/adr/` (`NNNN-title.md`; status, date, context, decision, consequences). Making a decision
creates its ADR; changing one updates it; reversing one marks it `superseded by NNNN` and writes the
new one, all **in the same change as the code**. A Decisions-table row below whose decision has an
ADR links to it, and a new decision gets both. Read the relevant ADRs before changing an area: they
say why it is the way it is.

## Decisions (agreed with Dewi, July 2026)

| Decision | Choice | Why |
|---|---|---|
| Scope | Both pillars from day one | Unified data model proven early |
| Stack | Kotlin + Jetpack Compose, single Gradle project | Native fit, strongest type safety |
| minSdk | **33** (Android 13; was 34 until 2026-10-14) | Personal modern devices, including Dewi's Pixel 4a on stock Android 13; Android-14 calls are guarded in `reminders/kit/ForegroundKind.kt`. [ADR 39](docs/adr/0039-minsdk-33-for-the-pixel-4a.md) |
| Extraction | **From-scratch library in this repo** (`:lib:ytdlp`), replacing dewijones92/youtubedl-android fork | Own a clean, tested Kotlin API around the real yt-dlp |
| Python runtime | Chaquopy 17 (revised from "official CPython" once that proved tier-3/no artifact) | Mature drop-in embed, MIT, pip support |
| ffmpeg | **Bundled** — minimal static build in jniLibs | Merges best-quality DASH streams (SponsorBlock is no longer cut from downloads: [ADR 16](docs/adr/0016-downloads-keep-sponsor-segments.md)) |
| CI/CD | GitHub Actions; signed APKs on GitHub Releases | No Play Store (yt-dlp app) |
| YouTube account (July 2026) | Own minimal InnerTube client (`:lib:innertube`) + **TV device-code OAuth**, SmartTube-style; yt-dlp stays for extraction/playback | Signed-in features (subs, history, comments, likes) need auth + writes; yt-dlp is read-only and removed OAuth login; Google blocks WebView logins, and the device flow is the login it expects from TVs |
| UI bar | Genuinely nice, modern | Material 3 expressive, dark/light, edge-to-edge, considered motion — never template-default |
| Voice detection (Sep 2026) | **Silero VAD run in plain Kotlin** (`SpeechModel`), weights exported to `res/raw/silero_vad.bin` | Smart skip-silence needs a neural speech detector; ONNX Runtime's arm64 library alone is ~12 MB compressed / 33 MB installed, about twice the agreed budget. The network is small (filter bank, 4 convs, one LSTM), and `SpeechModelTest` pins the Kotlin to ONNX Runtime's outputs |
| Exsurge et Disce (Oct 2026) | A **side-quest module**, pure `:lib:exsurge` + Android adapters; exact alarms; a sticky health-FGS banner holds the step counter; the voice is an alarm, not media; tests opt-in and path-filtered; the break length is chosen at the summons and fixed for that break | Dewi's stand-up-and-study nudge into Loquax. See [ADRs 2–8 and 10](docs/adr/_index.md) |
| Fast switch to video (Oct 2026) | yt-dlp's solver player cache on for every extraction; a resolve trusted until shortly before its URLs expire, and kept across restarts; one shared HTTP pool; caption translations only for offered languages; videos looked up before the tap (menu open, next in queue, audio copy) on unmetered networks; Watch offered for an audio copy | Downloaded audio → video took 16.7 s on Dewi's phone, 85% of it extraction; [ADR 11](docs/adr/0011-make-switching-to-video-fast.md) |
| JS challenge solver (Oct 2026) | **Android's V8** (`androidx.javascriptengine`) as a yt-dlp provider above QuickJS, keeping the solver library and two players loaded; QuickJS is the automatic fallback | QuickJS parsed a 3–4 MB script per solve (1.1–7.9 s on the Pixel 7); V8 solves a kept player in 15–80 ms; [ADR 12](docs/adr/0012-solve-youtube-challenges-in-v8.md) |
| Playback start (Oct 2026) | A 403 on a YouTube URL under 15 s old is retried when YouTube accepts it (issue + 4.8 s); the player is built through the preload manager so held bytes are played; a copy on disk is used before re-extracting, and takes over when its download lands | Fresh `web_embedded` URLs 403 for ~4.5 s, which the fast solver exposed; [ADR 13](docs/adr/0013-playback-start-young-urls-held-bytes-and-copies.md) |
| Gapless queue (Oct 2026) | In an item's last 75 s (longer than the 60 s silent tail that can end it early) the queue routes the next item as usual and **arms** it as the player's second playlist entry (Wi-Fi, or a downloaded next item); the player crosses over itself and the queue **adopts** it through its ordinary play path; a "Gapless queue" setting, on by default | Hand-overs took 0.5–1.3 s rebuilding the player even with bytes preloaded; [ADR 20](docs/adr/0020-gapless-queue-the-player-holds-the-next-item.md) |
| Background playback (Oct 2026) | The playback service stays in the foreground for 2 h after playback stops (Media3 default: 10 min) | After a long pause Android refused to restart it from the background and froze the process mid-file; Exsurge's own foreground service had been hiding this; [ADR 14](docs/adr/0014-playback-service-stays-foreground-after-a-pause.md) |
| Exsurge on the lock screen (Oct 2026) | The banner is an Android 16 **Live Update** in every state (lock screen, always-on display, status-bar chip), public on the lock screen; channel unchanged | A silent notification is hidden from a Pixel lock screen by default; [ADR 15](docs/adr/0015-exsurge-banner-is-a-live-update.md) |
| Daily alarms (Oct 2026) | A second side quest: Totum asks each weekday morning whether you want an alarm (pickup 17:30 first) and rings it itself full screen; built on a **reminder kit** shared with Exsurge (`:lib:reminders` + `app/…/reminders/kit/`); all alarms on **one board, always pinned**, soonest first; the board and the Exsurge banner alert silently so they show on every lock screen | Dewi's toddler-pickup alarm; [ADRs 17, 18, 21, 23](docs/adr/_index.md) |
| One-off alarms (Oct 2026) | ➕ New alarm on the alarms page: a title, recent titles, +15 min to +2 h or a clock, a day; a `DailyAlarm` with a date (`once`), so the same machine, board and ring; once rung it leaves the list and its title stays as a recent; also an app-icon shortcut, a Quick Settings tile and a Library tile | Dewi's "7 PM tonight, deployed to production"; [ADR 91](docs/adr/0091-a-one-off-alarm-is-a-daily-alarm-with-a-date.md) |
| Follow mark (Oct 2026) | Every row shows ✅ subscribed / ➕ not subscribed / ❔ unknown beside its channel or show, for both pillars; ➕ subscribes, ✅ asks first; one rule in `:core:domain` (`FollowedSources`) | Dewi wanted it obvious while scrolling; [ADR 22](docs/adr/0022-every-row-says-whether-you-follow-its-source.md) |
| Music tab (Oct 2026) | **Music** replaces Search in the bar (Videos · Podcasts · Music · Queue · Library); search is a 🔍 in every tab header, opening over the tab; the Music tab is `SearchViewModel` in a MUSIC scope (songs, albums, artists); album and artist pages from YouTube Music; your library via the TV client; music items are `MediaContentKind.MUSIC` and play as sound until Watch | Dewi's "nice way to play/search music/albums"; [ADRs 24–26](docs/adr/_index.md) |
| Radio, repeat, shuffle (Oct 2026) | Radio tops itself up from YouTube Music's continuation; repeat (off / queue / this one) is decided in `AutoAdvancer`; shuffle up next in the Queue header; repeat and shuffle serve both pillars | [ADR 27](docs/adr/0027-radio-repeat-and-shuffle-are-queue-features.md) |
| Shortcuts (Oct 2026) | Anything can be pinned (`Pin`, one sealed type for both pillars); a Pinned row on the Music home and Library, and home-screen icons (`totum://pin/<key>`); a pin plays at once through `PinPlayer`, a video opening the player | Dewi's Miss Rachel video and Pablo album; [ADR 28](docs/adr/0028-pins-and-home-screen-shortcuts.md) |
| Shuffle all (Oct 2026) | 🔀 on channel and podcast pages and a row on a selected group: `WholeSource` walks every page of every source, Shorts asked each time, nothing skipped, no cap, shuffled into one `shuffle:` queue group after what is playing | Dewi's "shuffle ALL videos of a channel"; [ADR 30](docs/adr/0030-shuffle-all-walks-every-page-into-one-queue-group.md) |
| Downloads clean-up (Oct 2026) | Cards at the top of Downloads suggest four groups (30+ days unopened, finished, over 500 MB, not queued), reviewed with everything ticked before deleting; deleting a queued item dequeues it; the download row records when it was downloaded and last opened | Dewi's "suggest group to batch delete"; [ADR 31](docs/adr/0031-downloads-suggest-groups-to-delete.md) |
| Music favourites (Oct 2026) | Like (song menu), Save (album page), Follow (artist page) write to the YouTube Music account through the TV client, and count only once a read-back shows the change; "Your favourites" tops the Music home | Dewi's "starring" on his real account; [ADR 32](docs/adr/0032-music-favourites-live-on-the-account.md) |
| Side-quest look (Oct 2026) | Roman and bold: a laurel gold (`LocalLaurel`) only the side quests use; the Exsurge hero is Surgius in a countdown ring; settings in icon cards with sliders on the old steps; each alarm a compact card with a huge next time and Edit | Dewi's "sexy while keeping functional"; [ADR 34](docs/adr/0034-the-side-quests-look-roman-and-bold.md) |
| Recently and Even out (Oct 2026) | Recent actions (shuffle all, albums, radios) come back as a Recently row and app-icon shortcuts; a third boost mode, Even out, rides the level passage by passage | [ADRs 35, 36](docs/adr/_index.md) |
| Exsurge nudges (Oct 2026) | Off stays off and never summons; while off, one silent "Exsurge is off" reminder when active hours start each day, and a phone flat for a minute in active hours gets a "Sat down?" nudge | Dewi was summoned while he thought it was off; [ADRs 37, 71](docs/adr/_index.md) (71 supersedes 38) |
| Signed-in TV streams (Oct 2026) | The signed-in TV player request is SmartTube's: its Cobalt agent, the web-scale timestamp and `isInlinePlaybackNoAd`; no PO token; ciphered links signed; signed in, the account answers **first** and yt-dlp is the backup | YouTube bot-blocked every anonymous client on the home IP, and the old request's links all returned 403; [ADRs 49, 50](docs/adr/_index.md) |
| Resume and per-show skips (Oct 2026) | Resuming talk rewinds 2–20 s by how long it was paused (in a session, or since the position was saved), never music; a show or channel can have its intro and outro skipped (set in Details), as skip segments through the SponsorBlock ticker; Explore follows a podcast into its show's next episode and can be capped; five seconds of silence in an item's last minute ends it (not music), and SponsorBlock end cards are skipped by default | Dewi's picks from the product review and his "dead time at the end of videos"; [ADRs 56, 57, 58](docs/adr/_index.md) |
| Transcript tab (Oct 2026) | Up next · Related · Comments · Transcript · Details; the Transcript tab only when one exists: YouTube captions (human in your language, else auto) or the feed's `podcast:transcript`; follows the spoken line, tap to seek, search | Dewi's pick from the product review; [ADR 60](docs/adr/0060-a-transcript-tab.md) |
| New uploads (Oct 2026) | One check (`NewUploads`) feeds a shuffled channel or show's new uploads next into its queue group, and chosen sources' (or every subscription's) into a "📥 New" group at the end; every 2 h on Wi-Fi, on opening, and ⋮ → 🔄 Pull new uploads | Dewi's Peep Show mealtimes; [ADR 59](docs/adr/0059-new-uploads-flow-into-shuffled-groups-and-an-inbox.md) |
| Drive backup (Oct 2026) | Everything but downloads and the queue, to a Drive folder (or one file) chosen in the system picker; latest + 7 dailies; on every change (30 s settle, 5 min max); restore merges; one shared backup for both phones | Dewi's "backup to my personal Google Drive"; [ADR 53](docs/adr/0053-google-drive-backup.md) |
| Out-of-date banner (Oct 2026) | The installed version is checked against GitHub's latest release at launch and every 30 min; when behind, a red, undismissable banner tops every tab with **Update** (Obtainium, else the release page); reports carry `update.*` | A report came from 0.1.602 with 0.1.603 already out; [ADR 29](docs/adr/0029-an-out-of-date-build-says-so-on-every-tab.md) |
| Stats (Oct 2026) | Library → 📊 Stats from an append-only JSON-lines log (`:lib:stats` pure, `StatsLog` in the app; not Room, so no schema bump), aggregated when the page opens; a play counts at 30 s or half; Exsurge back-fills from its outcomes | Dewi's "pretty rich stats page with drill downs"; [ADR 44](docs/adr/0044-stats-are-an-append-only-event-log.md) |
| State emoji (Oct 2026) | Every state string (status, setting value, empty/error, notification) starts with an emoji, in the string itself; nothing spoken or used as a description carries one; one vocabulary (✅ ❌ ⚠️ ⏳ 📭 …) | Dewi's "prefix … with emoji"; [ADR 43](docs/adr/0043-every-state-string-starts-with-an-emoji.md) |
| Music ⇄ talk (Oct 2026) | One tap jumps within the queue between music (`MediaContentKind.MUSIC`) and talk: the item of the other kind left last, else the nearest; no music queued plays the newest music from Recently; mini player, full player, Queue header and an extra notification slot | Dewi's "save me going in to the queue ui each time"; [ADR 42](docs/adr/0042-one-tap-switches-between-music-and-talk.md) |
| Queue drag and groups (Oct 2026) | The queue drags with `sh.calvin.reorderable` by real positions (Dewi's choice over a hand-rolled rewrite); a group header drags its whole group, and its ⋮ moves it to top / bottom / next or shuffles it; 🔀 also shuffles the order of groups; a split group's second header is hidden mid-drag | The hand-rolled drag jumped 88–90dp off the finger crossing a group; [ADR 40](docs/adr/0040-the-queue-drags-by-real-positions.md) |
| Dependency updates (Oct 2026) | Renovate at weekends, merging only its own green PRs (`platformAutomerge` off: `main` is unprotected); patch/minor merge by themselves after 3 days, security fixes at once, majors and the risky ones (Kotlin, AGP, Chaquopy, Media3 `needs-soak`, FFmpeg/QuickJS `needs-manual-rebuild`) are held and looked at at the start of every batch | It had never opened a PR: its Monday-before-6am window kept being missed; [ADR 41](docs/adr/0041-renovate-runs-at-weekends-and-media3-waits-for-a-soak.md) |
| Player tabs and Explore (Oct 2026) | Under the controls: Up next · Related · Comments · Details (Related in Listen mode and for songs too); **Explore** follows related videos as peeks and leaves the queue waiting, resuming after the item it left when switched off; toggles in the player, Queue header, mini player and Settings | Dewi's "section for related … what's playing next … a mode for automatically playing related videos"; [ADR 56](docs/adr/0056-player-tabs-and-explore-mode.md) |
| Videos hidden until asked (Oct 2026) | The Videos feed sits behind a **Show videos** button, hidden again whenever you leave the tab; a setting, on by default | Dewi's dopamine fast; [ADR 19](docs/adr/0019-videos-hidden-until-asked.md) |
| Video fullscreen (Oct 2026) | Landscape rotation opens immersive video; portrait exits automatic fullscreen; the button keeps its landscape lock | Rotation and a deliberate button press have different orientation ownership; [ADR 9](docs/adr/0009-rotation-follows-video-fullscreen.md) |
| UI performance (Oct 2026) | Screens read derived playback fields, players go through `SteadyPlayback` with position in `LocalLivePosition`; play/download states are a `State<Map>` read per row; video in a `SurfaceView`; nothing animates unseen (equaliser, busy bar still over the player); the position ticker sleeps while nothing plays | Peep Show stuttered on both phones: 130–150 ms frames every position tick; [ADR 61](docs/adr/0061-the-ui-does-no-work-per-playback-tick.md) |
| Quick strip, queue headings, stay in kind (Oct 2026) | Listen/Watch, speed, boost, skip silences and sleep sit in a strip under the player's title and left the tile grid; ungrouped queue runs get "➕ Added by you", the playing group's header says ▶️ and 🔊 Playing now; auto-play (and gapless arming) stays in music or talk, the other kind left queued, then Explore or stop; manual ⏭ unchanged | Dewi's "currently I have to scroll down", "not added to Peep Show", "if I'm in non-music mode … I don't want music"; [ADRs 62–64](docs/adr/_index.md) |
| Everything about the item (Oct 2026) | An "ℹ️ About this" card tops Details for any item (`itemFacts`); the description comes from the watch page when the TV player has none; a channel page stamps its channel on its rows | Dewi's "display everything about the item … a general rule"; [ADR 65](docs/adr/0065-the-player-says-everything-about-the-item.md) |
| Sort and filter (Oct 2026) | One sheet on every list header (`SortAndFilterSheet`, `MediaSort`, `ItemFilters` in `:core:domain`); queue sorts change the play order within each group, the playing item fixed, with Undo; queue filters view only; date added in Room v28; a header's buttons wrap under its title rather than squeezing it (`TitleAndActions`) | Dewi's "sort … a group in the queue by date added … any possible filter"; [ADR 66](docs/adr/0066-one-sort-and-filter-menu-everywhere.md) |
| Casting (Oct 2026) | The TV plays a static DASH stream the phone serves: original video packets relayed, Totum's processed audio as AAC segments; formats from measured TV profiles (VP9/H.264/HEVC; never AV1); a video the TV can't show goes as sound only with a "📺 Casting to …" strip saying why; the phone's Subtitles button drives the TV; tested without a TV on a Shaka-in-Edge stand-in (`tools/cast-bench/standin/`) | Dewi's "cast with Totum as the brain"; [ADR 68](docs/adr/0068-cast-a-stream-the-phone-serves.md) |
| PC screen (Oct 2026) | A receiver page the **phone** serves (`/screen` on the cast server, keyed), played with Shaka in Edge; the page is one more player (`ScreenPlayer`, `PcScreens`), fed the TV's stream; the Pi bookmark (`crashlog…/screen`, Google-gated) only redirects to the phone's latest page address, which the phone posts to `/ingest` | Dewi's "watch from my phone on my PC display"; an https Pi page cannot play the phone's http stream (mixed content); [ADR 81](docs/adr/0081-the-pc-screen-is-a-page-the-phone-serves.md) |
| Live streams (Oct 2026) | The signed-in TV answer for live carries no manifest, so Totum builds one (SmartTube's recipe): a head request (`X-Head-Seqnum`), a DASH manifest of `&sq=$Number$` segments served on loopback; a live item starts at the edge, never a saved place; live segments skip the chunked ranges | Live looped on one segment; [ADR 69](docs/adr/0069-live-streams-play-a-manifest-totum-builds.md) |
| Listen and Watch (Oct 2026) | Offered for anything with a picture, whatever its source: YouTube, a torrent of a video file, a video podcast (`Picture` in `:core:domain`, by extension; the player settles unknowns); Listen takes an audio-only address where there is one, else switches the video track off | Torrents and video podcasts had no switch, because it was gated on the YouTube pillar; [ADR 70](docs/adr/0070-listen-and-watch-follow-the-picture-not-the-pillar.md) |
| Playback cache (Oct 2026) | One 1 GB `SimpleCache` above the ranged fetcher for every stream (videos, podcasts, torrents); YouTube keyed by stream id + itag + length so a new signature still hits, anything else for this launch only; live, SABR, manifests and local files bypass it; the seek bar shows the buffer ahead (pale tangerine) and what played this session (cyan) | Dewi's "rewind without buffering … for anything"; [ADR 72](docs/adr/0072-a-playback-cache-under-every-stream.md) |
| Skip finished (Oct 2026) | Moving on (auto-advance, ⏭, gapless) passes over played talk; never music, never while a repeat is on; ⏮ and taps unchanged | Dewi's "skip items already marked played"; [ADR 74](docs/adr/0074-the-queue-passes-over-finished-talk.md) |
| Locked downloads (Oct 2026) | 🔒 on any download: kept from clean-up and every delete (one decorator, `LockingDownloadManager`), a delete asks to unlock; locks in their own prefs file | Dewi's Miss Rachel; [ADR 75](docs/adr/0075-a-locked-download-is-kept-until-unlocked.md) |
| Kid lock (Oct 2026) | 👶 in the player or a locked home-screen shortcut: a bare full-screen video, touches and back ignored, Android app pinning asked for (unpin with the phone's PIN lifts it); with pinning off, a 3 s corner hold unlocks | Dewi's Peppa Pig; [ADR 76](docs/adr/0076-kid-lock-pins-the-app-to-a-bare-video.md) |
| Download choice (Oct 2026) | ⬇️ asks 🎧 Sound or 🎬 Video (best/1080/720/480) with "remember"; once remembered it just downloads and press and hold asks again; one `DownloadChooser` for every list; the height is a ceiling passed to every strategy | Dewi's "choose audio, video and quality"; [ADR 77](docs/adr/0077-a-download-asks-sound-or-video-and-quality.md) |
| Captions (Oct 2026) | On in English by default for everything; switching off lasts one item; a chosen language carries on; the CC menu also lists a file's own subtitle tracks (torrent MKVs); no captions fetched in Listen | Report 0.1.686 and Dewi's "defaulted on English … come back on the next item"; [ADR 78](docs/adr/0078-captions-are-on-in-english-and-off-is-for-one-item.md) |
| Stars (Oct 2026) | ⭐ in every item's menu (and the player's Like) for both pillars; kept in a built-in "⭐ Starred" playlist, so it is backed up; a YouTube item's star is also its Like on the account; Library → ⭐ Starred merges the account's Liked videos | Dewi's "star anything"; [ADR 79](docs/adr/0079-a-star-for-anything-likes-youtube-items.md) |
| Episode downloads (Oct 2026) | A queue group's ⋮ and a torrent result's ⬇️ open one sheet: every episode ticked except what is on the phone, the ADR 77 choice for YouTube items (torrents and podcasts as files); four at a time; any interrupted download starts again by itself at the next launch, with its height, at most twice | Dewi's "download a whole series"; [ADR 80](docs/adr/0080-choose-episodes-to-download-and-resume-interrupted-ones.md) |
| Listen offers the picture (Oct 2026) | Starting something with a picture yourself while listening shows "🎬 … has a picture" over the mini player for 8 s with Watch / Keep listening; never music, never the app's own moves (`byYou = false`) | Dewi's "prompt me, do you want to switch to video mode?"; [ADR 84](docs/adr/0084-listen-offers-the-picture-when-you-start-it.md) |
| Recent searches (Oct 2026) | Every search box offers its recent searches as chips while empty: list filters (one list per kind of list), a channel's search, the transcript; a query is kept when it stood 2.5 s and found something, or on the keyboard's search key | Dewi's "every search in the app must contain recently searched"; [ADR 83](docs/adr/0083-every-search-box-offers-its-recent-searches.md) |
| Pi back end (Oct 2026) | The torrent chain the app talks to lives in `server/` here, one compose project each, deployed from the Pi's clone; nginx and gluetun stay in `dot-files` | Dewi's "front end and back end all in this repo"; [ADR 85](docs/adr/0085-the-pi-back-end-lives-in-this-repo.md) |
| Home-server tokens (Oct 2026) | A token per device, minted at the Google-gated sign-in and checked by `server/totum-auth` (nginx `auth_request`); only ever sent as the `X-Totum-Token` header (`HomeServerAuth`: OkHttp and the player's data sources), never in a link; 30 days unused and it expires; sign out per device | One shared, never-rotated token rode in every stream link and leaked; [ADR 90](docs/adr/0090-a-token-per-device-and-none-in-links.md) |
| Torrent search sources (Oct 2026) | Torrentio (Cinemeta for the IMDb id) is one more source beside each Prowlarr indexer, asked by the phone; a hash-only result gets a built magnet; the same info hash from two sources is listed once, best-seeded | Report 0.1.707's slow indexers; Dewi's "prototype it beside Prowlarr"; [ADR 86](docs/adr/0086-torrentio-beside-prowlarr.md) |; a result with only an indexer's download link (1337x) keeps that link and asks Prowlarr for the magnet when chosen ([ADR 89](docs/adr/0089-a-result-without-a-magnet-is-resolved-when-chosen.md))
| AVI audio (Oct 2026) | The AVI extractor is wrapped (`totumExtractors()`): an MP3 track stored as a byte stream is cut into whole frames before the decoder; every other container untouched | Report 0.1.709's XviD AVIs failed at 0 ms in Android's MP3 decoder; [ADR 87](docs/adr/0087-mp3-in-an-avi-is-cut-into-whole-frames.md) |
| Keeping your place (Oct 2026) | A page opened over a list goes through `ListThenDetail`, which keeps the list's saveable state (its scroll) for when the page closes, and gives the page system back; a channel's tabs and the music page stack keep one place each | Dewi's "remember where I was … in general through the app"; [ADR 88](docs/adr/0088-a-page-opened-over-a-list-keeps-its-place.md) |
| Library hub (Oct 2026) | The Library tab fits on one screen (Recently, Pinned, tiles); Downloads is a tile with a count line and opens its own page | The download manager under the tiles made every other Library page a long scroll away; [ADR 73](docs/adr/0073-the-library-is-a-hub-and-downloads-have-their-own-page.md) |
| Brand (July 2026) | **Bright and playful** — tangerine hero, cyan counterpart, lemon highlight; **dynamic colour OFF by default** | Dewi's explicit choice. Dynamic colour would substitute the wallpaper's palette on every modern device, so a defined brand would never actually be seen. Palette lives only in `theme/Color.kt` |

## Quality bar (from the brief, non-negotiable)

- **Unified, always** — this and DRY are the project's twin laws. The app has two
  pillars but every capability gets ONE seam that serves both: one domain model
  (`MediaItem` is a podcast episode *and* a video), one playback path
  (`PlaybackController` + one mini player), one search port (`SearchSource` →
  sealed `SearchHit`), one HTTP text port, one URL type. Before building any
  feature, ask "what is the pillar-agnostic seam?" and build that; pillar
  specifics live in small adapters behind it. A feature implemented twice —
  once per pillar — is a design failure even if neither copy shares a line.
  **Every feature must be unified across YouTube/video and podcasts by default.**
  A pillar-specific split is allowed *only* when there is a genuinely strong
  technical reason it cannot be one seam — and when that happens you must stop
  and surface the reason to Dewi for a decision, not silently build two paths.
  (Dewi, explicit, 2026-07-24.)
- **Testing pyramid, every time**: many fast unit tests; fewer integration tests; few
  instrumented/UI tests. New behaviour lands with tests, and a bug fix lands with a test
  **written first and seen to fail against the old code** — otherwise it is a description of
  the fix rather than a guard on it. Dewi has asked for this repeatedly; it is not optional.
  Where several components answer one question, test the ANSWER end to end as well as each
  part: on 2026-08-09 six green unit tests sat on top of a bug only the end-to-end test saw.
- **Strictly DRY** — this matters a lot to Dewi. Knowledge lives in exactly one
  place: versions and SDK levels only in `gradle/libs.versions.toml`; Android
  build defaults (compileSdk/minSdk/Java level/lint policy) only in the root
  build's `androidDefaults`; shared code in a shared module (`:lib:common`),
  never copy-pasted. Before writing similar code twice, factor it — and if a
  duplication is ever deliberate, it must be recorded here with its reason.
  **This applies across front ends too**, not only within the app: the CLI and the Android
  app share one extraction stack, one bridge contract and one set of selection rules, and a
  second copy of any of them is a defect. When you find one place with a rule, go and count
  the others — on 2026-08-09 five separate pickers answered "which stream?" and four were
  wrong, which is exactly what a shared seam prevents.
- **SOLID**: small focused types, dependencies point inward.
- **Maximum compile-time safety** (the brief's "dependent types" translated to Kotlin):
  sealed hierarchies, value classes over primitives, exhaustive `when`, no platform types
  leaking, illegal states unrepresentable.
- CI must stay green; quality gates (lint, static analysis, tests) block merges.

## Performance (measured 2026-07-12, API-35 emulator)

- **Cold start** ~1.5s warm / ~3.3s first-ever. The embedded Python engine is
  never started AT launch, so startup pays nothing for it — keep it that way. Since
  2026-10-06 (Dewi's choice, ADR 11) it is warmed 10 s AFTER launch in the background
  (`TotumApplication.warmTheEngineSoon`, ~1.5 s on the emulator), so a session's first
  video does not pay ~2.7 s for it; never under instrumentation (`TotumTestRunner`).
- **Memory**: ~115MB idle; ~196MB once the Python interpreter is resident.
  Reasonable for an embedded CPython; watch it if it climbs.
- **APK**: release is **arm64-v8a only + R8 (minify + resource shrink)** →
  **~33MB**, down from 94MB. Debug stays multi-ABL/unminified for the emulator.
  Build a release for the emulator with `-PemulatorAbis` (adds x86_64).
- R8 verified end-to-end on-device (podcasts, Room, Media3, Chaquopy/Python
  search all survive minification). App keep-rules: `app/proguard-rules.pro`
  (kept minimal — library consumer rules handle Room/Media3/Chaquopy/Compose).

## Build & test

```bash
./gradlew assembleDebug          # build debug APK
python3 tools/ci/preflight.py    # what the Gradle gate CANNOT see — run before every push
./gradlew detekt lint test koverVerify assembleDebugAndroidTest   # the full local gate (matches CI)
./gradlew :app:assembleDebug     # the gate does NOT rebuild app-debug.apk: run this before installing after it
./gradlew connectedDebugAndroidTest  # instrumented tests (device/emulator needed)
./gradlew :core:playback:testDebugUnitTest -Ptotum.audioQualityTests   # skip-silence + boost on real speech (needs ffmpeg)
```

The live YouTube instrumented phase runs **nightly and by hand** (`gh workflow run ci.yml --ref main`), not on push
(2026-10-15): it leaves through the home broadband, which YouTube bot-blocked, so per-push runs were always red and
added requests to the blocked IP. A push run logs "live YouTube phase skipped on push".

`-Ptotum.exsurgeTests` does not ADD Exsurge's tests, it RESTRICTS every module's test task to them:
the opt-in filter includes only the requested phases. Run it as its own command; combined with another
module's `test` it reports "0 tests" for that module, which reads as a pass (2026-10-07). The reverse bites too (2026-10-22): `--tests '*ExsurgeControllerTest'` WITHOUT the flag runs none of them and
still succeeds, so a change to Exsurge passed locally and went red in CI. Any change under `exsurge` gets
`./gradlew test -Ptotum.exsurgeTests` before the push.

The audio-quality suite (`…playback.audioquality.*`) is excluded from `test` and runs in CI only when a
push touches the silence/boost code (`.github/workflows/audio-quality.yml`), so run it yourself whenever
you change that code, and name any new source file in this area so the workflow's `paths:` match it
(preflight fails otherwise).

**Suppressing a warning is Claude's call, case by case** (Dewi, 2026-10-21) — detekt, lint, compiler or any
other: when a rule fights the code's shape (a binary-format parser full of offsets, an adapter whose size is its
interface, a `Player` wrapper that is all overrides, a wake lock held across calls by design), suppress it on that
declaration and say why in the commit message; when it points at a real smell (a long method doing several things,
a tangled condition), fix the code.

**detekt autocorrects and still fails the run that found the issue**, per module and fail-fast: a
gate that is red on `ImportOrdering`/`Indentation`/`ArgumentListWrapping` alone usually goes green on
the next run without an edit, and a build that touched N modules can need N reruns. Only a rule detekt
cannot autocorrect (`LongMethod`, `TooManyFunctions`, `MaxLineLength`) needs a hand.

Run `tools/ci/install-hooks.sh` once per clone (`core.hooksPath -> .githooks`) and preflight then runs
on every `git push` automatically. The hook is deliberately **only** the sub-second checks: one that ran
the Gradle gate would be disabled within a day, and a disabled hook protects nothing. `--no-verify`
bypasses it.

**`preflight.py` is part of the gate, not an extra.** Dewi, 2026-08-18: *"shift left to catch stuff
faster"*. Two red builds that day were both invisible to `detekt lint test koverVerify`, because neither
was about Kotlin: a live test missing from the CI exclusion list, and a shell variable that does not
survive `android-emulator-runner` (it runs **each line** of `script:` in its own shell). Each cost an
eleven-minute round trip to learn something a local check answers in under a second. Preflight is
verified against all three known failure modes — see its docstring.

On-device testing matters: the podcast RSS bug (Android's Expat parser rejecting
`DocumentBuilder` bean-property toggles) passed every JVM test and only surfaced
when driven on the emulator. Verify real flows on a device, not just via tests.

### Before shipping a playback or extraction change: soak it against the build before

Dewi, 2026-10-07: *"are you testing thoroughly enough on the emulator before u ship? maybe not?"* — no.
Two regressions reached his phone that day (every lookup stuck at 360p; playback frozen after a long
pause) because each change was checked with one or two taps that proved its mechanism fired, never a
session, never the build before it for comparison, and never on an emulator set up like his phone.

**When the soak runs (Dewi, 2026-10-23):** each feature is pushed as soon as its gate is green and it has been checked
on the emulator, so Dewi can try it on his phone; the soak below runs **once at the end of a batch**, against the
release before the batch, in parallel with the Opus review (see "How a batch runs"). A regression the soak finds is
fixed with a test seen red first and pushed straight away. This replaces soaking before every playback push.

The soak itself, for a batch that touched extraction, resolving, playback, the queue or downloads:

1. **Build the previous build too**, without touching the working copy:
   `git archive <ref> | tar -x -C <scratch>/baseline && cp local.properties <scratch>/baseline/ && (cd
   <scratch>/baseline && ./gradlew -q :app:assembleDebug)`. Both are debug-signed, so `adb install -r`
   swaps them and keeps the sign-in.
2. **Soak both** on `totum-api35`: `ANDROID_SERIAL=emulator-5554 tools/emulator/soak.py run --label <x>
   --out <scratch>/soak/<x>.json --start-budget-ms 12000` (three passes by default, ~15 minutes). It plays
   the videos from past reports as shared
   links (the worst case for the fresh-URL window), clears saved lookups first so the runs are comparable,
   and records from the app's own `dewidebug` lines: time to sound, quality, video decoded, 403s, fatal
   403s, stalls, underruns, the 360p/TV fallback, and falling back to the disk.
3. **`tools/emulator/soak.py compare before.json after.json`** — failing on more passes, a median start
   1.5× + 2 s slower, or a lower quality is a regression and blocks the push. Put the table in the commit
   message. One pass is NOT enough: the same video by the same route started in 0.8 s and 2.6 s on two runs,
   and stalls and underruns come and go on the emulator in both builds. The first soak (one pass each) called
   six regressions, four of them noise; it also caught a real one, an all-clients retry firing on 6 of 10
   videos that already had 1080p.

**A change to how the queue hands over gets a queue soak too:** `ANDROID_SERIAL=emulator-5554
tools/emulator/soak.py queue --label <x> --out <scratch>/queue.json` builds a real YouTube queue through a
debug-only receiver (`QueueDebugReceiver`), seeks each item to 40 s before its end, and records every
hand-over (crossed over or not, silence, stalls, adopted) with gapless off and then on. 2026-10-07: off 4.6 /
3.7 / 4.4 s, on 0 / 0 / 0 s. It caught a race no device test reproduced (an item under 45 s never put its
successor in line, because the queue's adoption cancelled the arming); local files win that race, streams lose it.

**A schema bump needs the whole `:core:database` instrumented suite, not just its migration test (2026-10-16):** `VideoIdMigrationTest` and `DownloadDatesTest` write a database at the current version and roll it back by hand, so a new column breaks them with "duplicate column name"; v28 went red in CI that way while `DownloadsMigrationTest` was green locally. Add the column to `RollBack.kt` (androidTest) when you bump.

**A schema bump blocks the baseline (2026-10-13):** Room has no downgrade path, so the previous build crashes on the newer database. Roll it back in place rather than wiping it: force-stop, pull `databases/totum.db*` with `run-as … cat`, `PRAGMA wal_checkpoint(TRUNCATE)`, drop the new columns, `PRAGMA user_version=<old>`, and set `room_master_table.identity_hash` to the old build's hash (`RoomOpenDelegate(<old>, "<hash>"` in its `build/generated/ksp`); push it back and delete `-wal`/`-shm`. The new build re-migrates it. Also: in `echo "$(date +%T) exit $?"` the `$?` is `date`'s, not the soak's.

**Name the baseline by its commit, and expect a wide noise band (2026-10-14):** a baseline directory reused from an earlier day was v0.1.604, not the 0.1.605 it was reported as, so a "605 vs 606" soak measured three changes. Build the baseline fresh from the previous release's tag (`gh release list`, then `git archive v0.1.<n>`) into a directory named after it. The same build failed 3/30 and 8/30 an hour apart (all underruns and `edon5wb5Qsc` give-ups), and emulator CPU did not separate failed plays from passing ones (idle ~100% vs ~110% of 400%, app ~140% either way), so a gap of a few failures between builds is noise until a third run repeats it.

**Skip finished confounds the queue soak (2026-10-22):** a baseline run plays its items to the end, so the next build passes over all of them (ADR 74) and reports "no hand-over seen"; the debug play-all now marks its items unplayed first. Read the `[queue] … passed over N finished` line before calling a queue soak a regression.

**Queue auto-download confounds a soak (2026-10-14):** each soak play leaves its video in the queue, so later runs inherit more undownloaded items and the queue auto-download fetches them during the plays (0 and 1 downloads in the two v0.1.606 runs, 3 and 5 in the two later runs of the new build, whose failures were 7 and 7 against 3 and 4). Check the header's other services for `DownloadKeepAliveService`, and clear the queue or switch queue auto-download off before every run.

**A soak no longer reports its plays to Dewi's YouTube account (2026-10-28):** the emulator is signed in to his real
account, so every soak play used to land in his YouTube history and push his own videos off the page the app reads.
`soak.py run` and `soak.py queue` hold reports for their whole run (a prefs file, removed at the end); a run killed
half-way leaves the hold on, so delete `shared_prefs/totum_test_holds.xml` with `run-as` if one dies.

Keep the laptop idle while a soak runs (2026-10-12): a Gradle build or test run during a pass steals the emulator's CPU and makes that build's starts and underruns look worse, so build and test before or after, never during.

Two traps in the tool itself (2026-10-09): `soak.py run` **exits 1 when any play fails**, so chain the
queue soak after it with `;`, not `&&` (an `&&` silently skipped the baseline's queue soak); and the queue
soak drives the app through a broadcast, which a **force-stopped app never receives** (every hand-over
reads "never started"), so launch the app first. `compare` reads only `run` reports; compare queue
reports by hand, and re-run a queue soak before believing one lost adoption: a run with another
foreground service left over from instrumented tests adopted 2/3, the clean rerun 3/3.

**Switch Exsurge (and Daily alarms) off on the emulator before a soak, for both builds.** On 2026-10-07
the third of three soaks ran with Exsurge off and the first two with it on; the third looked far better
(30/30 started, 18 stalls vs 45-50) and none of that could be credited to the change. A summons can also
pause a soak video outright.

Keep the emulator honest: its header line lists the app's other running services, and Exsurge's banner
service masks foreground-service bugs (it hid ADR 14's freeze for a week). YouTube's experiments vary by
visitor and by day, so a soak cannot prove the phone will behave; it catches what a session on the same
network would. One tap proves a mechanism; it never proves the app.

### A schema change with no version bump makes the app un-openable — and it looks like nothing

Room **does** throw on an identity-hash mismatch (`IllegalStateException: Room cannot verify the
data integrity … Expected identity hash: X, found: Y`), and it throws when the DB is opened, which
on this app is during startup. Two things make it hard to read:

- **The crash can be invisible.** `com.dewijones92.uniapp` — the pre-rename app — is still installed
  on `totum-api35`, so when Totum died at launch the launcher fell through to THAT app, which looks
  almost identical and has its own empty database. I spent several minutes reading "No podcasts yet"
  and a stale schema as evidence about Totum. Check `adb shell pidof com.dewijones92.totum` and
  `dumpsys activity activities | grep topResumedActivity` before believing any screen.
- **Amending an already-applied migration is what causes it.** Editing the body of a migration whose
  version a dev device has already run (fine to do while the commit is unpushed) leaves that device
  on a schema whose hash no longer matches. The cheap fix keeps the sign-in: force-stop, then
  `run-as com.dewijones92.totum rm -f databases/totum.db*` — the DB goes, `shared_prefs/` and the
  YouTube token stay — and re-add whatever test data you need.

### The emulator: `totum-api35`, and its YouTube sign-in is perishable

The project's emulator AVD is **`totum-api35`** (API 35, x86_64). Boot it with
`-gpu swiftshader_indirect` — the hardware GPU segfaults on sustained 4K/live decode and takes the whole
emulator down mid-test.

**It is signed IN as of 2026-08-20 17:26.** Do not trust that sentence — this section has been wrong in
both directions within a day, so **check** rather than read: a non-empty `youtube_account.xml` in
`run-as com.dewijones92.totum ls -l shared_prefs/` is the only answer that counts. It was signed out
twice in two days, each time by an unclean kill (WSL restarting) bringing the emulator back on an older
snapshot, once with the app not even installed. A sign-in survives an ordinary reboot only if the
emulator is shut down cleanly.

**But losing it is now cheap, and that is the point.** The token is backed up off-device at
`~/.credentials/totum-emulator/youtube_account.xml` (600, outside the repo — it is a real OAuth
credential), and `tools/emulator/restore-youtube-signin.sh` puts it back in about two seconds. The
**refresh token is long-lived**, so a restore works months later: the app exchanges it for a fresh
access token on the next call. Restore first; ask Dewi for a new code only if the restore fails.
Verified end to end on 2026-08-20 — restore, then `SignInOnThisDeviceTest` returns immediately with
`signin already signed in — nothing to do`, which is the cheapest signed-in check there is.

**A fresh sign-in is expensive, whoever holds it.** It needs a device code approved by hand at
google.com/device: the automation browser profile is signed out, so nobody but Dewi can do it, and a
code is single-use and short-lived. So treat the token as an asset:

- **`./gradlew connectedAndroidTest` UNINSTALLS the app when it finishes**, which destroys the sign-in.
  So does `pm clear` and any reinstall that wipes data. Check `run-as com.dewijones92.totum ls
  shared_prefs/` for a non-empty `youtube_account.xml` before and after anything drastic.
- **A test must never touch the real token store.** `SharedPrefsTokenStore` takes a `prefsName` for
  exactly that reason — `ASignInSurvivesTheProcessTest` clears its own file, and the version that used
  the default signed the device out (2026-08-19, throwing away an approval from minutes earlier).
- **Never run instrumented tests via `./gradlew` while signed in.** Use
  `adb shell am instrument -w -r -e class <FQN> com.dewijones92.totum.test/com.dewijones92.totum.TotumTestRunner`
  against an already-installed APK, and `adb install -r` (never `-t` fresh) when the APK must change.
- To sign in without the UI, run `SignInOnThisDeviceTest` and watch logcat for `dewidebug signin code`.
  It drives the real `YouTubeAccount.signIn()`, which persists the tokens itself — and it early-returns
  when already signed in, so it doubles as the signed-in assertion. Back the token up again afterwards.

**A Compose change is not verified until the screen has been LOOKED AT.** 2026-09-07: eleven queue UI
tests were green while every queue row shipped solid red with its drag handle hidden (cbf9916). A test
asserts the state it was written to assert; a screenshot (`adb exec-out screencap -p`) catches what nobody
thought to assert. Screenshot the changed screen on the emulator before calling UI work done.

Signed-in state matters for more than convenience: the subs feed, history, comments and likes all need
it, and (since the TV `/player` works again, 2026-09-06) `docs/todos/youtube-requires-attestation.md` turns on whether a **signed-in TV SABR session** behaves
differently from an anonymous one — which cannot be tested on a signed-out device.

- JDK 21 lives at `/home/dewi/code/jdk/`; Android SDK at `/home/dewi/code/android-sdk`
  (see `local.properties`, not committed).
- The `android` CLI (`~/.local/bin/android`) is available for emulators, screenshots,
  layout inspection, and docs search.

## Architecture

- `:app` — Compose UI: `AppShell` bottom navigation (Videos / Podcasts / Music / Queue / Library),
  search as a header action over the tab (`TabNavigation`), theme, screens.
- `:core:domain` — pure-Kotlin (JVM) unified media model: `MediaSource`
  (VideoChannel | PodcastFeed), `MediaItem`, `Subscription`, `SourceId`. No
  Android dependency — leakage is a compile error. `explicitApi()` is on.
- `:core:data` — pure-Kotlin (JVM): `RssParser` (hardened DOM), the
  `PodcastRepository` with its `HttpTextFetcher`/`PodcastStore` ports, OkHttp
  fetcher, and the unified search seam: `SearchSource` port returning sealed
  `SearchHit`s (Podcast | Video), implemented by `ItunesPodcastSearchSource`
  (iTunes directory API) and `YtDlpVideoSearchSource` (engine `ytsearch`).
  Also `SkipSegmentSource` (SponsorBlock-backed, fail-open: lookup failure =
  no segments). Business logic lives here, testably, off Android.
- Skip segments (`SkipSegment` + `skipTargetFor` in `:core:domain`) are
  enforced in exactly one place — `Media3PlaybackController`'s position
  ticker — so any pillar's playback skips them.
- **Downloads** (`DownloadManager` port in `:core:data`, `RoomDownloadStore`
  in `:core:database`): one seam, one port, taking a `PlayableItem` — the
  handle is both the pillar and, for a video, the watch URL to fetch from.
  The manager takes a single `DownloadStrategy`; `RoutedDownloadStrategy`
  (wired in `AppContainer`, the only place pillar routing lives) picks by
  `PlayHandle.pillar` in an exhaustive `when` — `EngineDownloadStrategy`
  for video (yt-dlp fetches best video+audio and merges via the bundled
  ffmpeg; since ADR 16 it no longer cuts SponsorBlock segments, and files on disk skip them live
  from the stored list that `SkipSegmentsOnDisk` refreshes), `HttpDownloadStrategy` for podcast
  enclosures (a plain HTTP GET). A third pillar cannot be added without that
  `when` failing to compile. `DownloadState` in `:core:domain`; playback
  prefers the local file wherever one exists — decided by `routeNow` below, for
  both pillars, so a downloaded video needs no re-resolution. Interrupted downloads (a `Downloading` row at
  startup) start again by themselves, with their variant and height, at most twice, then are left Failed for Retry
  (ADR 80; until 2026-08-31 they were dropped, then left Failed).
  Strategy IO runs off the main thread (`flowOn(Dispatchers.IO)`). Verified
  offline in airplane mode; video merge verified on-device (AV1 4K + Opus →
  one Matroska, played locally).
- **A download record carries its item**, on the same `PlaylistItemColumns`
  the queue, playlists and history use (four tables, one mapper). So
  `observeDownloaded()` returns `DownloadedMedia` (item + path + variant) and
  the Library tab lists **both pillars** from the download store alone, with
  no catalogue to join against — it previously joined podcast episodes, which
  is why a downloaded video was on disk and invisible. `DownloadedMedia.offline`
  supplies the local handle, so an audio-only video plays as audio and a full
  one as `LocalVideo`.
- **"How do I play this, right now?" is answered once**, by `routeNow` in
  `:core:domain` (`PlayRoute.kt`), for both pillars: it takes any copy on disk,
  whether there is a network and whether Listen mode is on, and returns one of
  four routes (`VideoFile` | `AudioFile` | `VideoStream` | `AudioStream`) or a
  `Refused` with its reason. `PlaybackQueue.route` is the only caller and does
  nothing but carry it out. It exists because the question had **one branch per
  pillar and the branches disagreed**: the podcast branch asked the download store
  for a local copy and the video branch never did, so a downloaded YouTube item
  was refused in airplane mode with its file on the disk (reported 2026-08-06).
  The handle-to-disk swap is shared with `DownloadedMedia.offline`
  (`playedFromDisk`), so the Library and the queue cannot drift apart again.
  One deliberate asymmetry, Dewi's call: an **audio-only** copy of a video does
  not stand in while you are *watching* (it would silently drop the picture) —
  it does the moment you are listening or offline.
- **"Is this a video?" is answered once**, by `MediaItem.pillar` in
  `:core:domain`, and "where do the bytes come from" once by
  `PlayableItem.fetchUrl`. Both used to exist twice with rules that disagreed
  (`youtube.com/watch` vs any YouTube host), so a Shorts URL downloaded as a
  video but queued as a podcast enclosure. Anything holding a `PlayHandle`
  reads `PlayHandle.pillar` instead — it knows exactly rather than guessing.
- **ffmpeg IS bundled** as a minimal static binary (`libffmpeg.so` in
  `app/src/main/jniLibs/<abi>`, ~7MB; built from FFmpeg 7.1.1 by
  `tools/ffmpeg/build-ffmpeg-android.sh`, remux-only — no decoders/encoders).
  **ffprobe is bundled too** (`libffprobe.so`, another ~7MB per ABI): yt-dlp's
  ModifyChapters postprocessor — the one that cut SponsorBlock segments out of
  a download until ADR 16 — asks ffprobe for the media duration and fails with "ffprobe not
  found" without it. Together they are ~30MB of the repo's 36MB working tree,
  which is why the tracked binaries look disproportionate and are not a mistake.
  PyPI has no `aarch64-linux-android` ffmpeg wheel, so it can't be
  pip'd; a shipped binary is the only way. Under Android 14 W^X the only
  app-private executable location is `nativeLibraryDir`, so the `.so` is
  extracted there (`packaging { jniLibs { useLegacyPackaging = true } }`) and
  `FfmpegBinary` symlinks it to the name yt-dlp expects; the engine passes
  `ffmpeg_location`. This unblocks merged best-quality video and download-side
  SponsorBlock removal. The interpreter and ffmpeg can't self-update (W^X);
  yt-dlp itself (pure Python) still can.
- `:core:database` — Android library (Room via KSP): entities, DAO, and
  `RoomPodcastStore` implementing `:core:data`'s `PodcastStore` port. The only
  place entities meet domain types. Verified by instrumented tests; exempt from
  the Kover JVM gate.
- `:core:playback` — the unified playback seam: `PlaybackController` port +
  `PlaybackState`, implemented by `Media3PlaybackController` connected to a
  `MediaSessionService` (`PlaybackService`, foreground, Media3-managed
  notification with title/artist/artwork, seek back 10s / forward 30s, audio
  focus + becoming-noisy handling). Both pillars play through it — anything
  with a `MediaItem.mediaUrl` is playable, and every system surface
  (notification, lock screen, Bluetooth/headset media keys, Assistant)
  controls that one session. `POST_NOTIFICATIONS` is requested once as the shell
  composes (`RequestNotificationPermissionOnce`) — required on API 33+ or the
  notification never shows. It used to be asked for at FIRST PLAY, which was a
  defect: the dialog is another activity, so it paused ours and released the video
  surface, and pressing play on a fresh install stopped the picture. CI was red on
  it for five days reading as "the stream stopped". `fake.FakePlaybackController` for tests/previews.
  Kover-exempt adapter (Media3 glue; instrumented-verified).
  **Video renders on the same seam**: `PlaybackState.hasVideo` (from the track
  list) drives a `PlayerSurface` (media3-ui-compose) in the one `FullPlayer` —
  podcasts show artwork/controls, videos show the picture; no separate video
  player. The port exposes the `Player` so the UI binds a surface; the surface
  must appear before the decoder reports a size, so `hasVideo` (not video size)
  gates it, defaulting to 16:9 until `videoAspectRatio` is known. Streaming is
  pre-muxed quality; merged best quality comes via downloads (ffmpeg).
- Manual DI: `AppContainer` (in `:app`) wires the graph; construction is code,
  errors are compile-time. No Hilt/Koin.
- **Cleartext HTTP is deliberately permitted** (network_security_config):
  podcast enclosures in the wild are frequently plain http (BBC media hosts
  included); refusing them breaks playback of legitimate feeds. Same policy
  as AntennaPod.
- `:lib:ytdlp` — from-scratch yt-dlp library (replaces the youtubedl-android
  fork). **Pure JVM on purpose** — it is the platform-neutral API (types,
  port, fake); only the real engine module needs Android. Public API:
  `YtDlpEngine` (suspend `extract`, `searchVideos`, cold-`Flow` `download`,
  sealed results), `bestPlayableFormat()` selection. Deliberately independent
  of `:core:domain` (standalone, reusable).
- `:lib:ytdlp-chaquopy` — the real engine: yt-dlp on embedded CPython 3.12
  via Chaquopy 17 (MIT). `totum_ytdlp.py` is a thin JSON-in/JSON-out bridge;
  `BridgeJson.kt` parses it (JVM unit-tested); `ChaquopyYtDlpEngine`
  implements the API. Chaquopy constraints: exactly ONE module per app may
  apply the plugin; build-host Python minor version must match the target
  (3.12 here); NOT configuration-cache compatible (config cache disabled in
  gradle.properties because of this). ABIs: arm64-v8a + x86_64. Adds ~80MB
  to the APK (Python runtime per ABI). **yt-dlp self-updates at runtime**:
  `YtDlpUpdater` fetches the latest wheel from PyPI on every launch (background,
  SHA-256-verified, never starts Python) into a cache dir; `totum_bootstrap.py`
  prepends it to `sys.path` before `import yt_dlp` so a YouTube-breaking fix
  applies on the next start without an app update — a bad wheel is dropped and
  the bundled copy used. The interpreter and ffmpeg can't self-update (W^X).
  ffmpeg is bundled separately in `:app` jniLibs (see Downloads above).
- `:lib:innertube` — pure-JVM **YouTube account seam**, the second engine
  beside `:lib:ytdlp` (which stays read-only): signed-in capabilities — subs
  feed, history, comments, likes/actions — talk YouTube's private InnerTube
  API here, authenticated SmartTube-style via Google's **TV device-code OAuth
  flow** (user visits google.com/device and types a short code; no WebView —
  Google blocks embedded logins; endpoints + public TV client identity
  live-verified 2026-07-13). Auth layer: `YouTubeAuth` port +
  `HttpYouTubeAuth` (OkHttp), `DeviceLogin` cold-flow driver owning all
  protocol pacing (poll interval, slow_down backoff, expiry; transient poll
  failures retried until the code expires), `TokenStore` port (app supplies
  storage; access token ~1h, refresh token long-lived, `invalid_grant` on
  refresh = signed out, re-login). Token value classes redact themselves in
  `toString()` so credentials can't leak into logs. Fakes for tests/previews.
- `:lib:exsurge` — **Exsurge et Disce**, the side quest (not a pillar): after 30 minutes of
  sitting, Surgius (a Roman-orator mascot) takes over the screen and sends Dewi into Loquax
  (`dev.hanzi.hanzi_practice`) for five minutes. Pure JVM: one sealed state machine and the stats.
  `app/…/exsurge/` holds the Android adapters. Two things here are deliberately not the shared
  seam: its **voice clips play on their own `MediaPlayer`** (alarm sounds, never media; ADR 3), and
  its **state is not in Room** (ADR 2). It pauses and resumes playback only through
  `PlaybackController.setPlaying` and `PlaybackInterruption` (ADR 4). Its tests are an opt-in phase
  (`-Ptotum.exsurgeTests`) with their own workflow (ADR 7). Feature doc:
  `docs/features/exsurge-et-disce.md`.
- `:lib:reminders` + `app/…/reminders/kit/` — the **reminder kit** shared by the side quests (ADR 17):
  waveforms, choice lists, next-weekday-time maths and time serializers; exact and alarm-clock alarms, an
  alarm-channel voice (clips and text-to-speech), the alarm tone, the buzzer, chips, a clock picker, a
  lock-screen helper and a JSON prefs store.
- `:lib:stats` — the stats log's events and every aggregation, pure; `app/…/stats/` records them and
  `app/…/ui/stats/` shows them (ADR 44).
- `:lib:dailyalarms` — **Daily alarms** (ADR 18): `DailyAlarm` and the per-day state machine.
  `app/…/dailyalarms/` holds the controller, `RingService` (systemExempted foreground service that owns
  the ringing), the question and ring screens, notifications, receiver, tile and settings.
- `server/` — what the app talks to on the home Pi (Prowlarr, FlareSolverr, TorrServer and its watchdog,
  totum-audio), one compose project each, deployed from the Pi's `~/code/totum`; nginx and gluetun stay in the
  Pi's `dot-files`. See `server/README.md` and ADR 85. The crash sink is `tools/crashlog-server/`.
- `:lib:common` — pure-Kotlin utility module with no app dependencies, shared
  by app modules and standalone libraries alike (it would be published
  alongside `:lib:ytdlp`, like the old youtubedl-android's `common` module).
  Home of `HttpUrl`, the single validated URL type used everywhere.
- detekt, the Android lint policy, and Android build defaults
  (compileSdk/minSdk/Java level) apply to every module automatically from the
  root build — never configure them per module.
- Package root: `com.dewijones92.totum`.

## Working agreements

- **Iterate quickly and keep shifting left** (Dewi, 2026-10-21: *"we want to iterate quickly … any big cycles that
  take time … find bottlenecks and shift left"*). Treat a slow loop as a defect: measure where the minutes go
  (2026-10-21: a 1-line change rebuilt in 17 s and installed in 2 s; the minutes were the full gate and UI-driving
  scripts), then move the check earlier and cheaper — a laptop prototype for an unknown (the Python spike servers that
  settled the TVs' formats in seconds), a debug broadcast instead of screen taps (`DEBUG_CAST`, `DEBUG_QUEUE_PLAY_ALL`;
  `tools/cast-bench/bench.sh`), a JVM test instead of a device run, the full gate only before a push.
  When a cycle is slow, fix the tooling in the same session rather than paying it again.
- Commit as you go — small, coherent commits at each green state.
- **How a batch runs** (Dewi, 2026-10-14, 2026-10-15 and 2026-10-23):
  1. **Start with a discussion.** Before a batch, list what it will contain and ask Dewi the questions only he can
     answer (scope, defaults, trade-offs), with `AskUserQuestion`; then write each decision into its
     `docs/todos/<item>.md` and the session's task file, so it survives a context reset. Questions are for intent and
     defaults, never for facts the code can answer.
  2. **Each feature ships on its own** so Dewi can try it on his phone: tests (a bug fix's test seen red first), docs
     and ADRs in the same change, `./gradlew detekt` across all modules until clean, the full local gate, a look at
     the changed screen on the emulator, then push **only when the gate's exit code is 0**
     (`e=$?; [ $e -eq 0 ] && git push`). No soak per feature.
  3. **The batch ends with a soak and an Opus review in parallel**: the soak (see "Before shipping a playback or
     extraction change") against the release from before the batch, and a `reviewer` agent on the batch's commits,
     given the requests and the accepted decisions. Read the whole report, check each finding against the code, fix
     what holds up with a test seen red first, and push.
  4. **Then report** in the end-of-run shape (🚧 Needs you, Changed, 🔍 Found, ⚠️ Couldn't confirm), and mark the
     phone reports the batch answered as fixed, with the first release that contains each fix.
- Remote: `github.com/dewijones92/totum` (public; renamed from `dewiuniapp` on
  2026-07-25 — GitHub redirects the old URL, so stale clones keep working). Local
  clone is `~/code/totum`, and the Pi's is `~/code/totum`. The **signing**
  directories keep their old names on purpose (`~/code/dewiuniapp-signing`, the
  private `dewijones92/uniapp-signing-backup`): renaming anything holding release
  keys buys tidiness at the cost of risk. Default branch is
  `main`; pushing to it is fine and CI (GitHub Actions) must stay green.
- Every push to main publishes a signed APK to its **own** release, tagged
  `v0.1.<run number>` to match `versionName` (consumed by Dewi's Obtainium).
  It is deliberately **not** a prerelease, so it becomes the repo's "Latest
  release" and `/releases/latest/download/totum.apk` stays a stable URL.
  This replaced a rolling `latest` tag on 2026-07-27: Obtainium's default
  version string for a GitHub source is the release *tag*, and a tag that
  never changes means an update is only ever detected via non-default
  settings. Now the tag, the release title and the installed app all agree.
- **Never re-run an older `main` CI run while one is in flight.** `ci.yml` sets
  `concurrency: cancel-in-progress` on `${{ github.ref }}`, so re-running an old
  run *cancels the current one* — and since the tip run is what publishes the
  rolling APK, that silently leaves Obtainium without a build. If an old run
  failed spuriously (it happens — a run can complete with zero jobs, which is a
  GitHub scheduling fault, not a test failure), just let the newer commit prove
  it: the tip is a superset. Learned 2026-07-25, at the cost of the Totum APK. A newer push cancels the older
  run too, but the release is published as soon as the build job is done, so what gets cancelled is the later
  instrumented tests, not the APK (2026-10-15: two "cancelled" runs had both released). Check
  `gh release view <tag> --json targetCommitish` before concluding a fix did not ship. **But the build job
  ("Quality gates + build") takes ~17-19 min and publishes only at its end, so pushes closer together than that
  release nothing** (2026-10-23: four pushes ~15 min apart, three cancelled builds, no release for an hour while a
  crash fix waited). Leave ~20 min between pushes, or batch commits into one push. Release signing key lives in
  three places, never this repo: locally at `/home/dewi/code/dewiuniapp-signing/`,
  in this repo's Actions secrets (CI signing; write-only), and backed up in
  the PRIVATE repo `dewijones92/uniapp-signing-backup` (survives laptop
  loss). versionCode is `100 + run number`, so it only ever increases.
- **Be a polite client to YouTube** (Dewi, 2026-10-15: *"I want to be mindful of raising suspicion"*). Every
  YouTube call (InnerTube, yt-dlp extraction and solving, page walks, feeds, subtitles) goes through ONE global
  gate of at most **4 in flight**; nothing fans out past it, however many items a "shuffle all" or "play all"
  touches. Player bytes are outside it, and downloads keep their own cap of 4. A **429** (or `Retry-After`) means
  exponential back-off with jitter, never a tight retry; a **"not a bot"** refusal backs yt-dlp off (1, 2, 4 … 60
  min, reset by a success). Either one shows a banner on every tab while it lasts, and is logged and counted. A new
  feature that talks to YouTube uses the gate rather than its own `async` fan-out.
- **Every async operation shows itself** (Dewi, 2026-10-23: *"as a general rule of thumb in this repo … all async ops
  … should have ui indicators … notification or something"*). Anything the user starts that does not finish at once
  (a shuffle that walks pages, a page loading, a torrent preparing, a batch of downloads, a sign-in, a backup) reports
  through ONE seam, the live progress strip under every tab's header: what it is, a running count, Cancel, and a
  result when it ends (✅/❌, never silent). Work that outlives the screen also gets a notification. A new async
  feature that shows nothing while it runs is not done, and a second, private spinner is a DRY defect.
- **Text wraps; nothing truncates.** No `TextOverflow.Ellipsis` anywhere in `app/src/main`, and a
  `maxLines` cap only with a declared reason in `tools/ci/preflight.py` (Dewi, 2026-09-21: *"dont
  use ... please in the app - just wrap the text"*). A row grows to fit its title. Preflight fails
  on a reintroduction — not a Compose test, because a truncated `Text` still reports its whole
  string to the semantics tree, so no assertion about text can see the difference.
- **Log generously — err on the side of far more.** Dewi's standing instruction
  (2026-07-28): *"would we benefit from FAR MORE THINGS being logged??? in this repo,
  always err on the side of MORE LOGS/DIAGS."* This app is debugged almost entirely
  from diagnostics reports sent off a phone that is not in front of you, so an
  unlogged decision is an unanswerable question. It has cost real time twice: a
  23-second stall was invisible because nothing recorded stalls, and "the position
  clears when I switch tabs" could not be investigated at all because tab switches
  were not recorded. When adding any branch that decides something a user would
  notice — advance or don't, skip or don't, retry or give up, use this stream or that
  one — **log the decision and the reason for it**, not just the outcome. Prefer a
  line that says why nothing happened; silence is the hardest thing to debug.
  The one real constraint is the report buffer: it holds a bounded number of events,
  so anything firing many times a second must be counted and logged periodically
  (see `SILENCE_LOG_EVERY`) rather than dropped. Counted, never silent.
- **Every change must be provable in the wild from its logs and diagnostics alone —
  this is a MUST, not a preference.** Dewi, 2026-08-06: *"we need to collect
  logs+diags verbosely to make sure what we do works in the wild … this is a MUST!!!
  and be able to be read very well after the fact"*. A feature is not done when it
  passes tests; it is done when a report sent from his phone a week later can settle
  **whether it actually worked there**. So when you ship anything, ask: *if this
  silently misbehaves on his device, which line in the next report tells me?* If the
  answer is "none", the instrumentation is part of the change, not a follow-up.
  Three rules follow, each earned:
  - **Log the inputs, not only the outcome.** A decision line that omits what it
    decided from cannot be re-judged after the fact. "skipped it" was identical whether
    a downloaded file existed or not — which is exactly how the offline-video bug
    survived (2026-08-06). Now the line carries the copy, the network and the mode:
    `route <id> -> … [handle=Video copy=audio-only offline=true listen=false]`.
  - **A report must be able to answer the obvious next question.** Report 0.1.346
    carried the whole 97-item queue and every setting, and not one word about what was
    on the disk — so "was it downloaded?" was unanswerable and the diagnosis had to come
    from reading code instead. State blocks are cheap; add the counters AND the per-item
    detail (`downloads.queueStates`), because a total cannot say whether the item that
    was *tapped* was there.
  - **Write it to be read months later by someone with no context**: name the field
    (`playing=`, `copy=`), spell the units, prefer a whole phrase over a flag, and never
    let two different situations produce the same line.
- Debug logging must be prefixed `dewidebug`. **Keep it committed** until Dewi says
  otherwise (his standing rule, which reverses the earlier strip-before-commit one):
  these lines are often useful again. Make a chatty one reasonable — log only the
  interesting case, or lower its frequency — rather than deleting it.
- **A doc's `status` is a claim, and a stale claim is worse than none.** Twice on 2026-08-06 a
  status line was simply wrong: `public-domain-film-tv` said "app side not started" when the whole
  client, the zero-config host and the sign-in flow had shipped — which sent that session's opening
  recommendation in the wrong direction — and `audio-video-switching` said `refining` in its
  frontmatter while the index said "shipped". So: when you touch an area, **re-read its status
  against the code and correct it**, treat a disagreement between a file and the index as a bug in
  both, and when you find a status was wrong, say so in the doc rather than quietly fixing it — the
  next person needs to know the map has been unreliable there.
- **Every flow that matters gets an e2e in CI.** Dewi, 2026-08-06: *"make sure you have e2e of
  all these flows … in the ci/cd please"*. A flow with no e2e is one whose next regression is
  found on a plane. Where a flow depends on something CI cannot reach (the Pi, live YouTube),
  write **both**: a deterministic test against a stand-in that runs on every commit, and a live
  one behind `tools/ci/live-test-via-home.sh` that is allowed to skip. Never let the skippable
  one be the only coverage. Torrent tests use **copyright-free media only** — media this repo
  generates, and public-domain titles — and never resolve a real magnet. The map of what is and
  is not covered lives in `docs/tests/_index.md`, including what is deliberately uncovered.
- **Own the repo.** Dewi's explicit steer (2026-07-25): *"feel empowered to make big
  moves e.g. refactoring — you own this repo"*. So take the structurally right option
  rather than the timid one: collapse duplicated types, rename for honesty, move code
  to where it belongs. The guardrails stay — gate green, verify on-device, one
  coherent commit per move, and surface a decision that's genuinely Dewi's (a
  behaviour change to something shipped, or a trade-off with no clear default).
