---
title: A polite YouTube client
kind: feature
status: built 2026-10-15; the Pi shares it since 2026-11-07 (unit-tested; not yet seen on the Pi or the phone)
area: network/playback
updated: 2026-11-07
---

# A polite YouTube client

Dewi, 2026-10-15: *"I want to be mindful of raising suspicion in the YouTube company"*, after YouTube
bot-blocked the home IP. See [ADR 51](../adr/0051-a-polite-youtube-client.md).

- **One gate** (`PoliteGate`, `:lib:common`) for every YouTube API call: at most **4 in flight** and a
  token bucket of **2 per second** with bursts of 10. `PoliteInterceptor` gates OkHttp calls to
  `youtube.com`, `*.youtube.com`, `youtubei.googleapis.com` and `youtube-nocookie.com`. Video bytes
  (`googlevideo.com`) and images (`ytimg.com`, `ggpht.com`) are not gated. `PoliteYtDlpEngine` gates
  yt-dlp's extract, search, channel and solve calls, and a download's extraction (a permit until the first bytes). A refresh of every subscribed channel's feed now trickles
  (1,609 channels take about 13 minutes) instead of bursting.
- **429** (`Retry-After` honoured): every gated call waits 30 s, doubling to 10 min, ±20% jitter. The
  interrupted call is retried once if the wait is under 35 s. A success after the wait resets it.
- **"Not a bot"** (`BotBackOff`): anonymous yt-dlp extraction is not even tried for 1, 2, 4 … 60 min,
  reset by a success. yt-dlp no longer retries every client after a bot refusal (the IP is refused,
  not the client). Signed in, playback goes through the account anyway (ADR 50).
- **Look-ahead** (`ReadyAhead`) is skipped while YouTube asks us to slow down.
- **One visitor id per install** (`PrefsVisitorIdStore`) instead of a fresh one each launch.
- **Banner** on every tab while either lasts: "🐢 YouTube says slow down" or "🤖 YouTube bot check", each
  with the time it ends.
- **Diagnostics**: `polite.slowDown`, `polite.botBackOff`, and `polite.lastHour.<host>` (calls, 429s and
  other 4xx in the last hour, every host). Vitals `polite.calls`, `polite.waitedMs`,
  `polite.tooManyRequests`, `polite.botRefusals`, `polite.botBackOffSkips`. Gate waits are summarised at
  most once a minute.

Tests: `PoliteGateTest`, `BotBackOffTest`, `RequestMeterTest`, `PoliteInterceptorTest`,
`PoliteYtDlpEngineTest`, `ReadyAheadTest` (throttled), `VisitorIdSourceTest` (kept per install).

## Three lanes (2026-10-23)

Interactive, normal and background, served in that order; background (look-aheads, readying the next item, album
years, album-page ♡ lookups, new-upload checks) never holds more than half the slots. See ADR 51's update.

## The Pi too (2026-11-07)

`PoliteInterceptor`, `PoliteYtDlpEngine` and `Politeness` live in `:core:video`, so `totum serve` and the CLI commands
attach the same gate the phone does, through `Politeness.gated(engine)` and `Politeness.gated(clientBuilder)`. One
`Politeness` per process: `HomeDownloads` (resolver for targets and home downloads, the handed-over sign-in's history and
progress reports, yt-dlp), `YtDlpLiveHls` and `/health` all hold that one instance, and `HomeDownloads` and
`YtDlpLiveHls` require it, so a path that forgets it does not compile. A bot check from the Pi's InnerTube player answer,
yt-dlp extraction, a download or the live lookup backs all of them off. See [ADR 51](../adr/0051-a-polite-youtube-client.md)
(update of 2026-11-07) for what stays outside (bytes, OAuth, SponsorBlock) and what was left undecided (the phone and the
Pi each hold their own gate).

- **Seen on `/health`:** `youTube` with `inFlight`, `maxInFlight`, `queued`, `backedOff`, `slowDownUntilMs`,
  `slowDownReason`, `botBackOffUntilMs`; and as home problems on the phone, worded like the banner: "🐢 YouTube says slow
  down", "🤖 YouTube bot check" (signed in), "🤖 YouTube is blocking this network" (signed out, broken).
- **Logs:** the gate's own `[polite]` lines (waits, slow slots, 429, bot check, back-off skips) now appear in the Pi's
  output, because `serve` turns `Diag` on; `[health] home problems now: …` says when a back-off starts and ends.
- Tests: `PoliteDownloadTest` (a download holds a permit until bytes flow and reports its refusals; solves are gated;
  `Politeness.gated` wires the right gate), `YouTubeGateTest` (target resolve for a speaker and a TV, a home download,
  a progress report and the live lookup each wait for one shared single-permit gate and run holding it; a bot check seen
  by the live lookup, by extraction, by a download or by InnerTube's own answer stops yt-dlp everywhere),
  `HomeHealthYouTubeTest`, `HealthYouTubeGateTest`.
