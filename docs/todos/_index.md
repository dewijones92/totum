---
title: Backlog
kind: index
updated: 2026-09-07
---

# Backlog

Requested items not yet built (or in flight). One file per item. Move a `status`
to `shipped` and migrate it to `../features/` once it's a real feature on `main`.

| Item | Area | Priority | Status |
|---|---|---|---|
| [youtube-requires-attestation](youtube-requires-attestation.md) | playback | high | the ~1MB cap was the ANDROID/WEB endpoint, not attestation: SABR now resolves from the embedded player and streamed 4.2MB+ on device with no token (shipped 2026-09-07); the signed-in TV client is no longer refused (timestamp scale). Still open: quality tiers above what the embedded response offers, and seeking. Ordinary streaming WORKS via the yt-dlp fallback routes — the earlier "nothing un-downloaded streams" here was stale (corrected 2026-09-06; 1080p streamed on the emulator that morning) |
| [stalls-near-the-end-of-an-item](stalls-near-the-end-of-an-item.md) | playback | high | open — two defects fixed but disproven as the cause; instrumented for the next report |
| [Explore channel content](../features/channel-browse.md) | channel | high | shipped |
| [Views and dates everywhere](../features/upload-dates.md) | video/search/playback | high | shipped (incl. the video page, 2026-08-06) |
| [background-audio-listen-mode](background-audio-listen-mode.md) | playback | high | shipped |
| [fullscreen-video-stretch](fullscreen-video-stretch.md) | playback | high | shipped |
| [long-press-context-menu](long-press-context-menu.md) | ui | medium | shipped (all feeds; go-to-channel split out) |
| [go-to-channel-action](go-to-channel-action.md) | ui | medium | shipped |
| [url-share-target](url-share-target.md) | integration | medium | shipped |
| [play-history-screen](play-history-screen.md) | library | medium | shipped |
| [local-cross-pillar-playlists](local-cross-pillar-playlists.md) | library | high | shipped |
| [skip-silence-on-video](skip-silence-on-video.md) | playback | medium | shipped |
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
| [skip-silence-smoothness](skip-silence-smoothness.md) | playback | high | shipped — sample removal for audio, speed-up for video |
| [offline-queue-e2e](offline-queue-e2e.md) | tests | high | done — in CI, and it found a real bug |
| [buffering-defects-0.1.332](buffering-defects-0.1.332.md) | playback | high | all four fixed with tests |
| [settings-only-change-when-asked](settings-only-change-when-asked.md) | settings | high | done — speed, boost and brightness hold, incl. across a fullscreen toggle (fixed 2026-08-08) |
| [buffer-ahead-gauge](buffer-ahead-gauge.md) | playback | medium | shipped — seconds-ahead gauge on the scrub bar |
| [tab-state-preservation](tab-state-preservation.md) | navigation | high | shipped |
| [diagnostics-triage-state](diagnostics-triage-state.md) | diagnostics | high | shipped |
| [a-recovery-resumes-the-next-item-at-the-last-items-position](a-recovery-resumes-the-next-item-at-the-last-items-position.md) | playback | high | fixed 2026-09-06 — StreamRecovery drops stale failures |
| [outbound-progress-sync-is-dead](outbound-progress-sync-is-dead.md) | video | **critical** | **fixed 2026-09-06** — the TV-scale signatureTimestamp; outbox drained and the account's history shows it |
| [the-next-video-does-not-play-offline](the-next-video-does-not-play-offline.md) | playback | high | fixed 2026-09-06 — resume no longer waits on the account offline |
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
- [SABR cannot be opened part-way through](sabr-cannot-seek.md) — the units bug and four byte-machinery bugs are fixed; a cold jump is still served no media (wire-measured), our reader still cannot consume a granted seek, and the warm-jump lead is **REOPENED** — it was crossed off on an unsound instrument
- [The signed-in TV /player answers UNPLAYABLE](signed-in-player-is-unplayable.md) — **fixed 2026-09-06**, it was the stamp's scale; age-restricted fallback re-measured working
