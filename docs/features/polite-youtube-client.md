---
title: A polite YouTube client
kind: feature
status: built 2026-10-15 — on main once pushed; not yet seen on the phone
area: network/playback
updated: 2026-10-15
---

# A polite YouTube client

Dewi, 2026-10-15: *"I want to be mindful of raising suspicion in the YouTube company"*, after YouTube
bot-blocked the home IP. See [ADR 51](../adr/0051-a-polite-youtube-client.md).

- **One gate** (`PoliteGate`, `:lib:common`) for every YouTube API call: at most **4 in flight** and a
  token bucket of **2 per second** with bursts of 10. `PoliteInterceptor` gates OkHttp calls to
  `youtube.com`, `*.youtube.com`, `youtubei.googleapis.com` and `youtube-nocookie.com`. Video bytes
  (`googlevideo.com`) and images (`ytimg.com`, `ggpht.com`) are not gated. `PoliteYtDlpEngine` gates
  yt-dlp's extract, search and channel calls. A refresh of every subscribed channel's feed now trickles
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
