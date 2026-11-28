---
title: 🏠 The Pi cannot play or download YouTube videos that are only offered over SABR
kind: todo
area: pi/video
priority: medium
status: open 2026-11-28 (found in the real-device run of that day; not built, not started)
updated: 2026-11-28
---

# 🏠 The Pi cannot play or download YouTube videos that are only offered over SABR

The phone can resolve and stream a signed-in YouTube video that YouTube offers **only over SABR**; the Pi's `totum serve` cannot.
When YouTube's SABR-only experiment strips the direct URLs from the signed-in TV answer, the Pi resolves nothing from that
answer and falls back to yt-dlp's HLS, which is then cast through Totum's HLS relay
([ADR 156](../adr/0156-one-casting-brain-the-pi-casts-with-the-phones-own-code.md), the 2026-11-28 amendment, `HlsRelay`). That works
but it is the slower, lower-quality, yt-dlp-shaped route, and a video that yt-dlp cannot fetch either is simply lost to the Pi.

## What the code does today

- The phone wires SABR into its resolver and its downloads: `sabr = AndroidSabrRoute` (`app/.../di/AppContainer.kt:1244`, the
  download routes) and `sabrEnabled = { appPreferences.settings.value.sabrPlayback }` (`AppContainer.kt:1296`, the `VideoResolver`).
- The Pi builds the same two pieces without it: `sabr = SabrRoute.NONE` for the download strategy
  (`cli/.../serve/HomeDownloads.kt:170`) and no `sabrEnabled` for the `VideoResolver` it builds in `resolverFor`
  (`HomeDownloads.kt:175-190`), so `VideoResolver`'s default `{ false }` (`core/video/.../VideoResolver.kt:65`) holds and
  `overSabr` returns null at its first line.
- With the account's TV answer stripped of direct URLs, `SignedInTvPlayer` logs
  `<id> as <client> -> OK but nothing fetchable; no signed-in answer` and returns null, which is the line seen on the Pi
  as `as downgraded TV -> OK but nothing fetchable`. The resolve then drops to yt-dlp.

## What a fix would take

Not built; this is the shape, to be refined before any code.

1. **A Pi `SabrRoute` for downloads.** `AndroidSabrRoute` copies a `SabrStream` to a file, and gets its stream from `sabrStreamFor` in
   `:core:playback`, which is Media3 and `@UnstableApi`. The reader itself (`SabrStream`) lives in `:lib:sabr`. The Pi's `:cli`
   does not depend on `:core:playback`, so the route would be a small JVM implementation over `:lib:sabr` that the phone and the Pi
   share, with `AndroidSabrRoute` reduced to the Android-only part (one seam, not a second copy of the copy loop).
2. **`sabrEnabled` on the Pi's resolver**, from a Pi setting, not the phone's `sabrPlayback` preference. Its own default has to be
   decided on evidence, because `overSabr` is off the ordinary path for a reason: a resume position is a seek and SABR cannot seek
   yet ([sabr-cannot-seek](sabr-cannot-seek.md)), and a stalled SABR session is remembered and extracted instead.
3. **How the Pi's cast reads a SABR stream.** A cast item the Pi resolves over SABR has no plain URL for the relay or the stream
   server to fetch, so either the download route is used (fetch to disk, then cast the file, which the Pi already does for home
   copies) or the stream server needs a SABR source. The first is the small one.
4. **Politeness.** SABR requests are YouTube requests and take the same one gate as everything else
   ([polite-youtube-client](../features/polite-youtube-client.md)); the Pi's `Politeness` is already attached to its engine and
   client builder, and the SABR route has to be given it too.
5. **The Pi's dry run on real videos** that are SABR-only today, signed in through the phone's hand-over, before any default is set.

## What the log must say first

The Pi's line does not say **why** the signed-in answer had no URLs. Two causes look the same in it and want opposite fixes:

- **Stripped:** YouTube sent a `/player` answer with `serverAbrStreamingUrl` and no `url` or `signatureCipher` on the formats (the
  SABR-only experiment). SABR is the answer.
- **Unsolved:** the formats carried a `signatureCipher` or an `n` parameter that Totum failed to solve, so
  `withSolvedChallenges` left nothing in `directlyPlayable`. A SABR route would hide a solver defect rather than fix it
  (see the yt-dlp engine and solver policy, ADR 189, written in the same batch and not in this change).

So a resolve log line is wanted, before any of the above: for each answer that ends in "nothing fetchable", the counts of formats
that had a direct `url`, a `signatureCipher`, an unsolved `n`, and only a SABR address, and whether the answer carried a SABR
address at all. That settles which videos on the Pi are actually in this state and how many, from a report, without a guess. It
belongs in `:core:video` with the other resolve lines and is a separate change from this item.

## Not decided

- Whether the Pi should ever stream over SABR (a long item cannot seek, a resume is a restart) or only use it to download.
- Whether yt-dlp's HLS through the relay is good enough that this stays low priority: the 2026-11-28 relay is only proved against a
  fake upstream, and a real SABR-only video through it is not yet seen.
