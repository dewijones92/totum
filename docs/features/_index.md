---
title: Features
kind: index
updated: 2026-08-09
---

# Features

Status of every feature. `shipped` = on `main` and verified. Detail docs exist
for the larger / in-flight ones; small shipped features are tracked by this row
alone until they need more.

| Feature | Area | Status | Detail |
|---|---|---|---|
| Unified media model + playback (one controller, mini/full player) | playback | shipped | — |
| Podcasts: subscribe, RSS parse, episodes, refresh | podcasts | shipped | — |
| Videos: signed-in feeds (Home/Subscriptions/Watch Later/History) | video | shipped | — |
| YouTube TV device-code OAuth | auth | shipped | — |
| Unified search (iTunes + InnerTube search, yt-dlp fallback → `SearchHit`) | search | shipped | — |
| Search history (recent queries, idle-state chips) | search | shipped | [search-history.md](search-history.md) |
| Torrents: public-domain film & TV via the home server | torrent | shipped | [torrents.md](torrents.md) |
| Downloads (video merge + SponsorBlock cut / podcast enclosure) | downloads | shipped | — |
| Comments, related, like/dislike, Watch Later, subscribe | video | shipped | — |
| Playlists (account) | video | shipped | — |
| Channel page (subscribe + uploads) | channel | shipped | — |
| Chapters (yt-dlp + Podcasting 2.0 `psc`/remote) + seek-bar markers | playback | shipped | — |
| Playback queue (unified up-next) | playback | shipped | — |
| Streaming reliability (chunked fetch, hardware-aware codec, expired-URL recovery) | playback | shipped | [streaming-reliability.md](streaming-reliability.md) |
| Permanent vs transient failure, and playback that goes nowhere (ended / failed / stalled) | playback | shipped | [failure-handling.md](failure-handling.md) |
| Diagnostics: nav / place / view-model / queue-intent trails | diagnostics | shipped | — |
| Tabs remember where you were (per-destination saved state) | ui | shipped | — |
| Shorts reel (full-screen vertical pager) | video | shipped | — |
| Skip-silence (audio-only, A/V-safe) | playback | shipped | — |
| Sleep timer | playback | shipped | — |
| Per-source playback-speed memory | playback | shipped | — |
| New-content notifications (background refresh, both pillars) | notifications | shipped | — |
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
| Views and dates everywhere (every list, and the video page) | video/search/playback | shipped | [upload-dates.md](upload-dates.md) |
| Crash + diagnostics reporting (verbose reports to the Pi) | infrastructure | shipped | [crash-reporting.md](crash-reporting.md) |
| Row status (pillar / played / offline on every row) | ui | shipped | [row-status.md](row-status.md) |
| Feed pagination (account feeds + channel tabs) | video | shipped | [feed-pagination.md](feed-pagination.md) |
| Brand: Totum name, palette and icon | branding | shipped | [brand.md](brand.md) |
| Download notifications (progress / done / failed, aggregated) | downloads | shipped | [download-notifications.md](download-notifications.md) |
| Quality + speed as an on-video overlay | ui | shipped | [video-settings-overlay.md](video-settings-overlay.md) |
| Subtitles / captions (menu on the video, cues over the picture) | playback | shipped | [subtitles.md](subtitles.md) |
| Audio tracks: the right language by default, and a menu to change it | playback | shipped | [audio-tracks.md](audio-tracks.md) |
| Settings that stay put across the next video (speed, boost, captions, quality, track) | playback | shipped | [settings-that-stay-put.md](settings-that-stay-put.md) |
| Command line: `totum "jazz live stream"` on Linux/macOS, same libraries as the app | cli | shipped | [command-line.md](command-line.md) |
| Picture-in-Picture (video keeps playing when you leave) | playback | shipped | [../todos/feature-gap-review.md](../todos/feature-gap-review.md) |
| Offline library across both pillars (downloads carry their item) | downloads | shipped | [../todos/library-downloads-podcast-only.md](../todos/library-downloads-podcast-only.md) |
| Loading feedback (global busy bar; "go to channel" 12.5s → 59ms) | ui | shipped | [loading-feedback.md](loading-feedback.md) |
| Feed cache (the Videos tab opens with content, not a blank) | video | shipped | [feed-cache.md](feed-cache.md) |
| Offline queue (audio fetched automatically, readiness in words) | downloads | shipped | [offline-queue.md](offline-queue.md) |
| Quiet podcasts made audible, automatically (measures the item, cannot clip) | playback | shipped | [loudness-boost.md](loudness-boost.md) |

\* Cast: **tapping the button still crashed the app until 2026-07-28** — this footnote claimed
otherwise for weeks, because the fix that was made (a themed context for the button) never
covered the picker dialog, and nobody tapped it again to find out. Two crashes were stacked:
`IllegalStateException` (the picker is a DialogFragment, so the activity must be a
`FragmentActivity`) and, once past that, `IllegalArgumentException: background can not be
translucent` (MediaRouter themes the dialog from AppCompat attributes on the **activity**).
Both fixed in `8d900c5`, verified by actually tapping it. Real casting still unverified — no
hardware. Disconnect-loses-playback fixed earlier.

See [`../todos/`](../todos/_index.md) for smaller requested items not yet features.
