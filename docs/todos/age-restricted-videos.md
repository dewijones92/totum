---
title: Age-restricted videos
kind: todo
area: video
priority: high
status: shipped — age-restricted videos play on-device (fallback was DEAD 2026-08-18 → 2026-09-06: TV timestamp scale)
updated: 2026-09-06
---

# Age-restricted videos

PipePipe and SmartTube play them; Totum does not. Dewi pushed back on an earlier claim that this
was impossible, and he was right to.

**The mechanism is now fully understood and proven end-to-end outside the app**: an
age-restricted video's stream was fetched with the account this app already holds — HTTP 206,
204,800 bytes at ~1MB/s. What is left is engineering, not discovery.

## The proven recipe

Measured 2026-08-01 against `rwcfPqbAx-0` (age-restricted, free — NewPipeExtractor's own test
video), with `jNQXAC9IVRw` as a control on every single step.

1. **Ask InnerTube's player endpoint as the DOWNGRADED TV client, signed in.**
   - `clientName: TVHTML5`, `clientVersion: 5.20260707` — *not* the current `7.x`
   - `User-Agent: Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version`
   - `Authorization: Bearer <the app's existing account token>`
   - `contentCheckOk: true`, `racyCheckOk: true`
   - `playbackContext.contentPlaybackContext.signatureTimestamp` — **mandatory**
   → `status=OK`, 7 formats, **every one with a plain URL, no SABR**.
2. **Solve the `n` query parameter** on each URL against the current player JS.
3. **Fetch.** → `HTTP 206`, real bytes.

The client version is the whole trick. The *same request* at the current `7.20260707.07.00`
returns SABR-only (one URL out of seven). At `5.20260707` it returns seven fetchable URLs. This
is exactly why SmartTube keeps a `TV_DOWNGRADED` client in its list and tries it *before* `TV`.

### signatureTimestamp is not optional, and its absence lies to you

> **And since ~2026-08-18 it must be on the TV scale.** `20697001` where the player script says `20697`;
> the web-scale value is refused with the same "page needs to be reloaded" as no value at all, which is
> how this fallback was dead for three weeks while looking like an attestation wall
> ([tv-client-player-is-refused.md](tv-client-player-is-refused.md)). Re-measured 2026-09-06 on
> `rwcfPqbAx-0`: downgraded TV + `20697` → UNPLAYABLE; + `20697001` → OK, 7 formats, 7 plain URLs.
> `SignatureTimestamp.tv` carries it; the TV player calls take the type so the web number cannot be passed.

Without it the response is `UNPLAYABLE: "The page needs to be reloaded"` — **on any video,
including unrestricted ones**. Two earlier rounds of this investigation were spent on results
gathered without it, which is why `TVHTML5` appeared to refuse age-restricted content when it was
actually refusing *everything*. Fetch it from the player JS (`HttpSignatureTimestampSource`
already does this; it was 20662 on 2026-08-01).

**Always run a control video through any new client.** Every wrong turn in this investigation
came from testing one restricted video and reading a generic failure as an age-gate failure.

## What is left to build: an `n` solver

The URLs carry a raw `n` parameter and 403 until it is transformed. Stripping `n` does not help —
that 403s too.

`n` is solved by running a function extracted from YouTube's `base.js`, so it needs a JavaScript
engine:

Both reference implementations were measured rather than assumed, and only one of them can help:

- **yt-dlp needs one of four external runtimes.** Its n-solving lives behind a provider
  architecture (`extractor/youtube/jsc/`) whose providers are `deno`, `bun`, `node` and
  `quickjs`; the pure-Python interpreter is gone. **Totum already bundles the last of those** —
  see below, which is the answer.
- **NewPipe's Rhino solver silently no-ops.** Its age-restricted test being `@Disabled` was the
  hint; the measurement is worse. Running NewPipe's own
  `YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated` against today's
  player returned the URL **completely unchanged** — `0LWViwGcLwfTJ6Q` in, `0LWViwGcLwfTJ6Q` out,
  and the resulting URL 403s. node's solver on the same input returns `7CPc-qh0yq_Jdg`, which
  fetches. Its regexes no longer match the current `base.js`, and the failure is silent because
  the method returns the original URL rather than raising.

An earlier version of this document called Rhino "the route with a working precedent". That was
wrong, and only running it proved so.

### The app already ships a JS runtime — use it

Found while reading report 0.1.295, which carries the line
`[engine] JS runtime: /data/user/0/com.dewijones92.totum/files/qjs-bin/qjs`.

**Totum bundles QuickJS** (`libqjs.so`, symlinked as `qjs` out of `nativeLibraryDir` by
`QuickJsBinary`, exactly the trick used for ffmpeg) and hands the path to yt-dlp through
`totum_ytdlp.configure_js_runtime`. QuickJS is one of the four runtimes yt-dlp's challenge
providers accept, so **the on-device yt-dlp can already solve `n` challenges**.

That makes the WebView unnecessary. The remaining work is a bridge function in
`totum_ytdlp.py` that takes a list of `n` challenges plus a player URL and returns the solved
values via yt-dlp's `jsc` director — the same call this investigation drove from the laptop, on
a runtime the app already carries. No new dependency, no WebView, and it stays on the yt-dlp
wheel that self-updates on launch.

The `NSolver` port is still the right seam; its implementation is a Python bridge call rather
than a browser.

### Superseded: the WebView route

Kept because it was measured and would work, but it is now the second-best answer.

The app is an Android app, and a `WebView` *is* a current JS engine. The reason that matters is
not raw execution — it is that it can run **yt-dlp's `yt.solver.core.js`**, which does not pattern
-match the player at all: it parses it (`meriyah`) and regenerates code (`astring`), which is why
it still works when NewPipe's regexes have stopped. Those are ordinary npm packages with browser
builds, so the bundle is WebView-compatible in a way it is explicitly not Rhino-compatible.

That also inherits yt-dlp's maintenance: the solver ships in the `yt_dlp_ejs` package alongside
the yt-dlp wheel the app already self-updates on every launch, so a YouTube player change is
somebody else's problem rather than a Totum release.

Cost to weigh before building: a headless `WebView` for JS execution is a new capability in the
app, it must run off the main thread with a real timeout, and it breaks the current rule that
extraction logic stays testable off-device. Worth confirming with Dewi before it is built.

## What is left, precisely (2026-08-01, from the emulator)

The streams are now reached: QuickJS solved the challenge on-device in 16.6s and turned **7 of 7
formats playable**. The video still does not play, and the reason is no longer about YouTube.

**1. The recovery path only feeds SABR.** `VideoResolver.fromPlayerResponse` recovers the player
response after an extraction failure and then hands it to `overSabrFrom`, which refuses (SABR is
off by default, and rightly). The plain URLs we now hold are discarded. It needs a **direct**
sibling that builds a `Resolved` straight from `streaming.directlyPlayable`.

**2. The TV player response carries no metadata.** Measured: `videoDetails` holds only
`videoId, channelId, lengthSeconds, thumbnail, isLiveContent, isPrivate, allowRatings,
isCrawlable, isOwnerViewing, isTvfilmVideo, isUnpluggedCorpus` — **no title, no author, no
description** — and `microformat` is empty. So `PlayerDetails` parses to null, which is also why
the log says "no videoDetails". Any direct path needs a title from elsewhere; the public `next`
endpoint (`InnerTubeClient.next`, WEB client, unauthenticated) already returns watch-page
metadata and is the obvious source, since only the STREAMS are gated, not the title.

**3. Solving is slow — 16.6s on the emulator**, because the solver parses a 2.9MB player script
every time. yt-dlp's solver returns a `preprocessed_player` precisely so it can be reused; the
bridge currently throws it away. Cache it per player build (it changes about weekly, like the
signature timestamp) and only the first solve of a session should cost anything.

## What was ruled out, with evidence

| Attempt | Result |
|---|---|
| `ANDROID_VR` anonymous (v1.60.19 and v1.65.10, ± `visitorData`) | `LOGIN_REQUIRED: Sign in to confirm your age` |
| `ANDROID_VR` + OAuth bearer (± matching client headers) | `HTTP 400` |
| `WEB_EMBEDDED_PLAYER`, `TVHTML5_SIMPLY_EMBEDDED_PLAYER` | fail on the **control** video too — not age-gating |
| `ANDROID` + OAuth bearer | `HTTP 400` |
| Every yt-dlp `player_client` (`tv`, `tv_simply`, `web_embedded`, `android_vr`, `mweb`, default) | all fail identically; yt-dlp's own advice is *"use --cookies"* |
| yt-dlp + `--add-header Authorization:...` | `HTTP 400` — the header is global, so a TV token is sent on WEB-client requests |
| Exchanging the OAuth token for cookies (`OAuthLogin`/uberauth) | `403 Error=badauth` — the token holds only the `youtube` scope |

**Auth belongs to TV clients only.** SmartTube's `AppClient.isAuthSupported` is exactly
`TV, TV_LEGACY, TV_EMBED, TV_KIDS, TV_DOWNGRADED`. Sending a bearer with any other client is what
produces the `HTTP 400` — the earlier "token/client mismatch" reading was right, and the fix is
not to authenticate a different client but to use a TV one.

**Upstream NewPipeExtractor has given up on this**: its age-restricted test is
`@Disabled("There is currently no way to extract age-restricted videos")`. It lacks an account;
we have one, which is the advantage to spend.

## The two videos that started this were never age-restricted

Report 0.1.289's failures, `skUpycGyI_A` and `goQ3z52qqD4`, are **Paramount+ paid content** —
"Yogurt Shop / Pizzeria" (a Nathan For You episode) and "The Dictator" (a film), both on the
`Paramount+ Global` channel. The signed-in TV client's `"This video requires payment to watch"`
was **literally true**, and an earlier version of this document dismissed it as misdirection.

No token, client or cookie will ever open those. When the app meets one it should say *"this
needs to be bought on YouTube"*, not fail generically — a separate, small piece of work worth
doing regardless of the n-solver.

## Where the code is

`InnerTubeClient` carries the earlier attempts (`playerAsAccount`, `playerEmbedded`,
`playerAndroidVr`, `playerWebEmbedded`) wired cheapest-first in `AppContainer.accountPlayer`. The
downgraded-TV call is the one to add; the others can go once it works.

## Status corrected, 2026-08-04

This said *"playback blocked on a direct-stream path + a title source"* long after both were built.
`VideoResolver.fromDirectStreams()` and `InnerTubeClient.playerDowngradedTv()` are in the tree and
the videos play — verified on-device when they landed.

A stale "blocked" is the most expensive kind of wrong note: the next session reads it, believes the
feature is broken, and either redoes finished work or designs around a limitation that no longer
exists. Checked against the code rather than memory.
