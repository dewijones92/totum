---
title: Switching audio to video, and opening a channel, feel slow
kind: todo
status: measured 2026-10-06 (emulator + phone reports); instrumented; fixes not chosen
area: playback/channel
updated: 2026-10-06
---

# "Open channel and switching from audio to video are slow"

Dewi, 2026-10-06: *"some things like 'open channel' and switching from audio to video are slow …
maybe measure it first???"*

## Instrumented (2026-10-06)

| Line | Says |
|---|---|
| `[latency] first picture for <id> 4700ms after play(), 36139ms after "switch to video (row)" (play() came 31439ms in)` | tap → first picture/sound, split into "before play()" (resolve) and "in the player" |
| `[nav] located "<title>" via <route> in Nms -> channel … (UC id known \| handle only)` | how "go to channel" found the channel: the listing, a subscribed feed, or a yt-dlp extraction |
| `[channel] opened "<title>" … (UC id known: tabs load via InnerTube \| handle only: videos via yt-dlp)` then `"<title>" videos via InnerTube: 30 item(s) in 375ms` | the channel page's route and each tab's load time (also next pages) |
| `watch: no video resolved here …` | the player's Watch used to return silently when nothing was resolved |

Taps are marked from the player (Watch/Listen) and the row (Watch with video / Listen).

## Measured on the emulator (`totum-api35`, 2026-10-06)

| Flow | Time | Where it went |
|---|---|---|
| Go to channel from the subscriptions feed | 0.4 s | locate 2 ms (the listing names the channel) + InnerTube 375 ms |
| Go to channel from the full player | 0.7 s | locate 282 ms + InnerTube 454 ms |
| First play of a video, app cold | ~34 s | resolve 29.6 s (yt-dlp extraction 25.2 s, Python + JS cold) + 4.4 s to sound |
| Listen, streams already resolved | 1.2 s | in the player |
| Watch, streams already resolved | 8.5 s | joining a 1080p VP9 HLS stream mid-video (software decode on the emulator) |
| **Downloaded audio → "Watch with video", app restarted** | **36.1 s** | extraction 26.0 s + rest of resolve 4.7 s + player 4.7 s; the audio keeps playing meanwhile |

The player offers **no Watch control** for a downloaded audio copy, so the row's "Watch with video" is
the only way to the picture, and it re-resolves from nothing.

## From Dewi's phone reports (Aug-Sep 2026, 171 resolves)

`play` resolves: median 4.3 s, p90 13.7 s, max 25 s (n=67). `describe`: median 13.1 s. Over SABR
("Fast start"): median 10.5 s (n=4), so the setting's "~150 ms start" (an emulator measurement) did not
hold on the phone. "Go to channel" by extraction measured 12.5 s in July (8 s Python + JS start, 4.4 s
extract); items whose listing names their channel skip that entirely.

Channels open quickly whenever the item carries its channel (`sourceUrl`). Items that do not: shared
links (placeholder), YouTube Music songs. Those pay a full extraction.

## Candidate fixes (not chosen)

- Resolve the video in the background while a downloaded audio copy of it plays (or when its player
  opens), so Watch finds it cached (the cache keeps 10 min).
- Warm the Python engine after startup, off the launch path (memory cost ~80 MB, deliberately lazy
  today: see CLAUDE.md, Performance).
- Give the player a Watch control for a downloaded audio copy.
- Start a mid-video switch at a lower quality and let it climb, rather than joining 1080p cold.
- Carry the uploader URL the resolver already has into the resolved item, and give shared links and
  songs their channel.
