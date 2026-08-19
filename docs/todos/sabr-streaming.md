---
title: yt-dlp needs a JavaScript runtime (kids videos were stuck at 360p)
kind: todo
area: video
priority: medium
status: partly shipped — fallback AND QuickJS both shipped; time-addressed seeking still open
updated: 2026-08-19
---

# The 360p problem, and what it actually was

## What it actually carries, measured 2026-08-19 on `totum-api35`

Driven through the real `SabrResolve` seam, at up to 1080p (the cap `SabrResolve` already enforces, and
what Dewi asked to test):

| Content | Result |
|---|---|
| 97-minute VOD | itag 137 **1080p30**, picture and sound |
| Made-for-kids (Ms Rachel) | itag 137 **1080p25**, capped down from 2160p |
| 19-second clip | itag 133 240p15 — everything YouTube offers for it |
| 4K60 upload (Big Buck Bunny) | itag 135 **480p30** — an honest ceiling, see below |
| Live stream | refused by name, falls back to extraction |

Sustained serving, which is what decides "comfortable" rather than merely "working": **11,315,189 bytes
of 1080p30 in 6 fetches, 60 seconds of media**, stopping only at the test's own target. The ~1MB refusal
that governs plain URLs does not apply here — that is what SABR is for.

**The 60fps and VP9/webm refusals were re-measured, not assumed.** Probing every rung of a 4K60 upload:
1080p60 mp4, 1080p60 webm, 1080p60 AV1, 720p60 mp4, 720p60 webm and 480p30 webm all returned 0 bytes;
480p30 mp4 served 117954B. So a 60fps upload legitimately comes back at 480p, because YouTube offers
30fps no higher for it. A ceiling, not a defect — `MAX_SABR_FPS`/`MAX_SABR_HEIGHT` stay as they are.

**Three of our own gates were in the way**, now fixed or named: a SABR-only response discarded before
SABR was ever asked, `lastModified` required when a live stream carries none, and live then needing an
explicit refusal. See [sabr-live-needs-an-init-segment.md](sabr-live-needs-an-init-segment.md).


Made-for-kids videos (Ms Rachel) played at 360p while SmartTube played them properly.

**It was never SABR, and never a YouTube policy.** An earlier version of this document
recommended implementing YouTube's SABR/UMP protocol, a multi-week project. That was wrong.
Dewi asked the obvious question — "you sure the yt-dlp CLI can't play 1080p?" — and the CLI
answered it:

    WARNING: No supported JavaScript runtime could be found. Only deno is enabled by
    default ... YouTube extraction without a JS runtime has been deprecated, and some
    formats may be missing

With `--js-runtimes node`, the same yt-dlp returns the full ladder to 1080p (avc1, vp9 and
av01). Without one it returns a single 360p stream. **Chaquopy cannot provide a JS runtime,
so the app is permanently in the degraded case.**

## Shipped: a second opinion that needs no JS

`PlayerStreams` / `InnerTubePlayerStreams` asks YouTube's `/player` directly as the ANDROID
client, and `VideoResolver` uses it **only when yt-dlp comes back with a single quality at
360p**. Measured 2026-07-30:

| video | our `/player` call | app's yt-dlp (no JS) |
|---|---|---|
| kids (Ms Rachel) | 32/32 urls, 1080p | 1 format, 360p |
| normal (Fireship) | 32/32 urls, 1080p | 23 formats, 1080p |
| music video | 30/30 urls, 2160p | 27 formats, 2160p |

Those URLs carry no `n` parameter, so nothing needs deciphering and no runtime is implied —
which is exactly why this works on a phone where yt-dlp cannot. Verified end to end: a
ranged GET returns HTTP 206 at ~29 MB/s, and on-device the resolver logs
`direct ask gave 6 qualities to 1080p, up from 360p`.

Deliberately a fallback, not a replacement: yt-dlp handles age gates, region locks,
signature ciphers and non-YouTube sources that this does not.

## SHIPPED: QuickJS, so yt-dlp itself works properly

> Confirmed 2026-08-06 from the code and from a device. `lib/ytdlp-chaquopy/QuickJsBinary.kt` ships
> the binary and links it where yt-dlp looks; `NSolver` (with its own tests) solves `n` for the
> InnerTube path; and report 0.1.346 records the runtime being used on Dewi's phone:
> `[engine] JS runtime: /data/user/0/com.dewijones92.totum/files/qjs-bin/qjs`. The reasoning below is
> kept for why it was worth doing.

yt-dlp supports `deno`, `node`, `quickjs`, `bun`, and looks for quickjs as a binary named
`qjs` at a path we can supply. That is **the machinery we already have for ffmpeg**: build a
static binary, ship it in `jniLibs` as a `.so`, expose it under `nativeLibraryDir` (the only
executable location under Android 14 W^X) and pass the path.

QuickJS is around a megabyte, against ffmpeg's seven. It would fix yt-dlp broadly rather
than one symptom — including `n`-parameter deciphering, which otherwise throttles downloads
— and it is insurance for the next thing YouTube changes, since yt-dlp has now deprecated
running without it.

## Where exactly the wall is (measured 2026-07-31)

The fast path was enabled as the primary resolver and it failed the same way it always had —
`[resolve] … in 1435ms BY ASKING YOUTUBE` and then `Source error`. This time the URLs were
probed directly, so the reason is no longer a guess.

| Request against a fresh ANDROID-client URL | Result |
|---|---|
| bytes 0–256K | **206** |
| the same 0–256K request, three times | **206, 206, 206** |
| bytes 0–896K | **206** |
| bytes 0–1024K | 403 |
| **first** request = bytes 512K–1M | **403** |
| then bytes 0–512K on that same URL | **206** |

**Only the first megabyte of the stream is reachable.** It is not a rate limit (the same
request repeats fine), not the request count (an early failure happens on request one), not the
range size in itself (a 896K range from zero is fine), not the User-Agent (identical with
yt-dlp's, ExoPlayer's, and none), and not the length probe that `ChunkedDataSource` already
stopped making by reading `clen` from the URL.

151 of 152 formats answer 206 to a first small range, which is exactly why "resolve" looked
like success for so long, and why a ladder to 2160p means nothing here.

The rest of every stream is behind SABR — which is what `serverAbrStreamingUrl` is for, and
what SmartTube implements. So:

- `/player` is genuinely ~150ms and gives a full ladder, a title, a length, captions and the
  channel id. It is a fine METADATA source.
- It cannot serve **playback** at any speed until SABR is implemented. Re-enabling
  `playerStreams` as a resolver produces a video that resolves fast and plays for one megabyte.

yt-dlp's URLs are durable because it uses a client (`WEB_EMBEDDED_PLAYER`) with a deciphered
`n` parameter — which is what the JS runtime buys and why extraction costs 2-4s. **That cost
is the price of a stream that plays to the end**, and no amount of restructuring around
InnerTube avoids it.

## The protocol works. Proven 2026-07-31.

Started implementing it, and the unknown part — whether we can talk SABR at all — is now
answered. Three findings, in the order they arrived:

**1. yt-dlp cannot help.** The bundled 2026.07.04 has zero SABR support (no
`serverAbrStreamingUrl`, no UMP, nothing). The upstream PR
[#13515](https://github.com/yt-dlp/yt-dlp/pull/13515) is **still open** — ready for review 13
July 2026, no milestone — and it is an `fd/` *file downloader*. It would serve yt-dlp
downloads, not ExoPlayer playback, so it cannot fix video start even once merged. This has to
be a Media3 `DataSource`.

**2. The inputs are all there**, on the ANDROID client's player response:
`serverAbrStreamingUrl`, a 12820-char `videoPlaybackUstreamerConfig` (9613 bytes decoded),
`enableVideoPlaybackRequest`, and 151 formats carrying `initRange`, `indexRange`,
`lastModified` and `contentLength`. The WEB client returns UNPLAYABLE and none of it.

**3. A minimal request returns real media.** POST to `serverAbrStreamingUrl`:

| Body | Response |
|---|---|
| empty | 31 bytes: `RELOAD_PLAYER_RESPONSE` → `sabr.malformed_config` |
| `field 5 = videoPlaybackUstreamerConfig` | **212246 bytes, 26 UMP parts** |

And the media in it is genuine, identified by magic bytes:

| Part | Magic | What |
|---|---|---|
| header 0 | `1a45dfa3` | WebM/EBML header |
| header 1 | `ftypdash` | fMP4 init segment |
| header 2 | `1f43b675` | WebM Cluster |
| headers 3, 4 | `moof` | MP4 fragments |

Audio and video, initialisation and fragments, interleaved in one response — from a body
containing **one field**. No PO token, no `ClientAbrState`, no format selection needed to get
bytes flowing.

## What has landed

`:lib:sabr`, pure Kotlin, no Android:

- `UmpVarint` — UMP's width-prefixed little-endian integer, which is **not** protobuf's and
  sits inches from it in the same response. The five-byte case discards its first byte
  entirely, unlike every other width; that is the one that would silently corrupt offsets.
- `UmpReader` — the `[type][size][bytes]` framing, reporting bytes it could **not** consume so
  a part split across HTTP responses is carried forward rather than dropped. That boundary
  occurs on every response and is the hardest corruption to notice.
- `UmpPart` — the part-type names, so a log says `SABR_ERROR` rather than `42`.
- `Protobuf` + `VideoPlaybackAbrRequest` — enough to write the body that worked. Hand-rolled:
  the schema is Google's private one with no public `.proto`, and a generator plus runtime
  would be a build dependency and APK cost for a handful of length-delimited fields.

Tested against the real 26-part sequence (types and sizes genuine, payloads synthetic — the
real bytes are somebody's copyrighted video and prove nothing the framing does not).

## What is left

1. ~~**Decode `MEDIA_HEADER`**~~ — done. `MediaHeader` reads headerId, videoId, itag,
   lastModified, byte offset, the init-segment flag and content length, verified against a real
   52-byte header. The mapping is confirmed by container magic rather than by plausible
   numbers: field 3 said itag 396 and the bytes that followed began `ftypdash`, while itag 249
   was followed by `1a45dfa3`.
2. ~~**Select formats**~~ — done, and `xtags` turned out to be the crux. See below.
3. ~~**State across requests**~~ — `ClientAbrState.player_time_ms` does it. See below.
4. ~~**A Media3 `DataSource`**~~ — done, and **a real video plays through it on Android**.
5. **PO token**: not needed. Never sent one, and full-quality media came back every time.

## It fetches real, decodable media. Verified 2026-07-31.

Everything the protocol needs is now proven, and the last three unknowns fell to probing:

**`xtags` is mandatory, not optional.** A real response carried **22 entries for each audio
itag** — one per dubbed language track. Selecting itag 251 by itag and `lastModified` alone
matched an arbitrary one of the 22 and the server answered
`RELOAD_PLAYER_RESPONSE: sabr.no_audio_selected`. With `xtags` (`acont=original`, `lang=en-US`)
in the `FormatId` it served exactly the requested track.

**`preferred_audio_format_ids` (16) and `preferred_video_format_ids` (17) are honoured;
`selected_format_ids` (2) is ignored.** Asking for itag 251 + itag 137 returned precisely
those two, 1.44MB in one request.

**`player_time_ms` must be inside `ClientAbrState` (field 28); the top-level field 4 is
ignored.** Four requests differing only in the top-level field returned byte-identical
responses. Moved inside, 0ms reached video byte 1271335 and 30000ms reached 8761825 — the same
request in every other respect.

**`enabled_track_types_bitfield` (40) = 1 gives audio ALONE** — 167876 bytes, one itag. Values
0, 2, 3, 6 and 7 all returned audio and video together, and no value was found that gives video
without audio. That is fine: playing a video needs both, so one request carrying both is
efficient rather than wasteful, and the two are separated by their `MediaHeader` itag.

### The proof

A request built by **our Kotlin encoder** (9715 bytes) was POSTed to the live endpoint:

```
itag 137: 1389065 bytes, magic 0000001c66747970  (ftypdash — fMP4 init)
itag 251:   34893 bytes, magic 1a45dfa39f428681  (WebM/EBML)
```

and the audio bytes handed to ffprobe:

```
codec_name=opus   codec_type=audio   sample_rate=48000   channels=2
format_name=matroska,webm            duration=1087.701
```

then decoded to PCM: **2.13s of 48kHz stereo, mean volume -14.7 dB, max -0.0 dB.** Real audio,
not silence. ("File ended prematurely" is expected — that was one segment.)

So the protocol layer works. What is left is plumbing it into Media3, which is engineering
against a known quantity rather than a research problem.

The prize remains what it was: a ~150ms resolve instead of 2-4s, and no JS runtime on the
playback path at all.

## It plays. On the device. 2026-07-31.

```
[sabr] opened at 0 of -1 bytes
[sabr] PLAYED 1187ms of itag 140 over SABR
```

`SabrPlaybackTest` (instrumented, `:app`) does the whole thing with no fakes: a live `/player`
call, the real `SabrStream`, the real `SabrDataSource`, a real `ExoPlayer`. The only assertion
that matters is the one it makes — **the playback position moved**. It reached 1187ms of itag
140 on "Me at the zoo", chosen because it is short and unlikely ever to be taken down.

So the chain is complete: `/player` in ~150ms → SABR request → UMP → media bytes → ExoPlayer →
audio out.

### What the failure taught, before it passed

The first run failed with `Source error` and nothing to explain it. The fix was instrumentation,
not guesswork: `SabrStream` now says what a response *did* contain when it contains nothing
useful — part names, itags and any refusal — because an empty result has three very different
causes (a refusal, media for a format we did not ask for, or a genuine end) and they are
indistinguishable otherwise. That line is what turned "Source error" into "itag 140 got no bytes
from 1562B, reasons=[…]".

Also learned: **itag 139 is refused outright** (`sabr.no_audio_selected`) while 140, 249, 251,
599 and 600 all serve, so a format chooser cannot assume every listed audio format is
obtainable.

## SHIPPED, behind a switch, for audio. 2026-07-31.

- **Seeking.** `SabrDataSource` is not seekable to an arbitrary byte: SABR is asked for a media
  TIME, not an offset. A reader opening at a position we have not reached gets nothing. Playing
  from the start works; scrubbing does not.
- **Video as well as audio.** The video path is written and the request is honoured, but only
  audio has been played end to end.
- **Adaptive switching**, which is the entire point of the "ABR" in SABR and currently unused —
  one format is picked and kept.
- **Then, and only then**, wiring it in front of yt-dlp for the ~150ms resolve.

## What actually shipped

**Settings → "Fast start (beta, no seeking)", off by default.** With it on, a YouTube video
resolves over `/player` + SABR instead of an extraction:

```
[sabr] prepared dc84PmnKlyo — audio itag 251, video itag none
[resolve] dc84PmnKlyo in 1839ms for describe OVER SABR
[sabr] serving dc84PmnKlyo itag 251 as AUDIO
[playback] playing at 1ms
[snapshot] playing "..." at 53845ms (running)
```

**1839ms against ~10s on the emulator**, and it sustains — 53 seconds in and still running.

With the switch off, nothing changes: verified on-device, `playing at 1ms` through the ordinary
extraction path and not a single `[sabr]` line.

### Audio only, and why

Video is written and served but **not shipped**: itag 137 arrives and ExoPlayer rejects it with
`Invalid NAL length` and `contentIsMalformed` — it reads valid mp4 and then meets a gap, so the
runs SABR returns for video are not byte-contiguous in the order they arrive and `SabrStream`
needs to hold them until they are. Shipping a video path that decodes to corruption would be
worse than shipping none.

### Two format rules, both measured rather than assumed

Probing every format of a real video:

| | Result |
|---|---|
| **video/webm (VP9)** — 313, 271, 248, 247, 244, 243, 242, 278, 598 | **every one refused** (`sabr.no_video_selected`) |
| video/mp4 (H.264, AV1) — 137, 400, 399, 398, 397, 396, 136, 135, 134, 133, 160, 394 | served |
| audio itag 139 | refused (`sabr.no_audio_selected`) |
| audio 140, 249, 251, 599, 600 | served |

So a listed format is not an obtainable one, and the chooser excludes VP9 for video and 139 for
audio. Note the asymmetry: `audio/webm` opus serves perfectly — the webm refusal is video-only.

### No custom URL scheme

`sabr://` would have read better, but `HttpUrl` is deliberately http(s)-only so every URL in the
app is known-good, and widening that invariant for one feature is a bad trade. The real SABR
endpoint is already https, so the session and track are marked on it as query parameters — and
the URL ends up honest about where the bytes come from.

## Still to do

- **Contiguous video assembly**, then video over SABR.
- **Seeking.** SABR is asked for a media time, not a byte offset, so scrubbing does not work —
  which is exactly why the switch says "no seeking" and defaults to off.
- **Adaptive switching**, the "ABR" half, still unimplemented; the quality menu is deliberately
  empty on this path rather than offering switches that would not work.
- Watch for whether a 403 seen once from the yt-dlp DOWNLOAD path during a SABR session is
  related; downloads use the watch URL and should be untouched, so it is more likely transient.

## Video works. And a range of videos was actually tried. 2026-07-31.

Dewi: *"make sure a range of videos work. live, 4k, ms rachael 1080p etc"*. Ten videos, and the
answer is eight.

| Video | Over SABR |
|---|---|
| **Ms Rachel (kids)** | **OK, 1080p** — on-device: `[format] video avc1.640028 1920x1080`, `playing at 119ms` |
| Ms Rachel, second video | OK, 1080p |
| Ordinary 1080p, music video, long podcast, old 240p | OK |
| 4K ×2 | OK, but capped at **480p** |
| **LIVE (24/7 stream)** | **UNPLAYABLE** — the ANDROID client returns no SABR endpoint at all, so it falls back to extraction |

### The bug that broke video, and it was not what I said it was

I had written that "the runs SABR returns for video are not byte-contiguous". Wrong. Measuring
the layout showed offsets ARE contiguous per format (807 + 100949 = 101756 exactly). The fault
was **attribution**: a `MEDIA` part's payload begins with **its own header id**, and runs
interleave arbitrarily —

```
MEDIA_HEADER id=3 ; MEDIA(3) ; MEDIA(1) ; MEDIA(1) ; MEDIA_END(1)
MEDIA_HEADER id=4 ; MEDIA(4) ; MEDIA(4) ; MEDIA(3) ; MEDIA_END(3) ; MEDIA(4)
```

— header 1's run resuming three parts after header 3 was declared. Binding each `MEDIA` to the
most recent header spliced one format's bytes into another's at the wrong offset, which decodes
as `Invalid NAL length` rather than failing cleanly. Audio-only hid it completely, because a
single format's runs arrive in order. I had also called that leading byte "a prefix that is not
media" — it is the routing information.

`contiguousFrom` now coalesces adjacent runs too, so a run that resumes later in a response is
not left stranded behind an offset key while the stream declares itself finished.

### Three more selection rules, all measured

- **Muxed formats are not SABR tracks.** Asking for itag 18 returned bytes ExoPlayer could not
  identify as any container (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`). Video picks must be
  video-only.
- **Every 60fps format is refused.** On a 4K/60fps video: 315, 337, 701, 308, 336, 700, 299, 303,
  335, 699, 298, 302, 334, 698 — all refused; 135/134/133/160 at 30fps served. **Declaring
  `MediaCapabilities` with a 2160p60 capability did NOT unlock them for any codec id 0-8**, so
  this is a server-side restriction and not a missing field on our side. Recorded so nobody
  repeats the experiment.
- **A refused video pick loses the audio too.** `audio 251` alone served 151007 bytes;
  `audio 251 + video 299` returned nothing at all. So one bad video selection costs the entire
  response — which is why the rules above are conservative rather than optimistic.

### Honest costs

- A 60fps upload plays at its best **30fps** rung, which on one 4K video was 480p. Worse quality
  than yt-dlp would give, but it plays, where before the request came back empty.
- **Live does not work at all** on this path and falls back to extraction, which is correct.
- Seeking still does not work, and the quality menu is still deliberately empty.

## Diagnostics, and what they immediately found

Dewi: *"put extensive logs/diags to ensure the ux is good"*. The instrumentation earned its
place within one run.

**What is logged now**

- **The quality actually delivered, against what YouTube offered**, and why it differs:
  `prepared LXb3EKWsInQ — audio itag 251 @149kbps, video itag 135 480p30, YouTube offered up to
  2160p — CAPPED, SABR refuses 22x 60fps and 17x VP9/webm formats; our own cap is 1080p`.
  A report saying only "SABR was used" would have hidden a 2160p video playing at 480p.
- **Every network fetch**: `fetch #4 itag 137 at 30000ms -> 8306200B response, 4760631B kept,
  2390ms`, flagged `— SLOW` past 3s. One line per round trip (~10s of media), not per read, so
  it is a few lines a minute rather than a flood.
- **A seek, by name.** Seeking is the known hole, and an open at a non-zero byte offset now says
  so loudly instead of presenting as "the player froze after I scrubbed".
- **A per-track summary on close**: fetches, reads, how many reads had to WAIT on the network,
  bytes served, bytes discarded, average fetch latency, media time reached.
- **A stuck stream** names the offset it wanted and the runs it is holding, rather than going
  quiet.
- Vitals for the report header: `sabr.fetches`, `sabr.fetchMs`, `sabr.bytesKept`,
  `sabr.bytesDiscarded`, `sabr.quality`, `sabr.seekAttempts`, `sabr.emptyResponses`.

**And it found a real cost nobody had measured.** On a 1080p video the fetches look like this:

```
fetch #3  7080300B response, 6743775B kept
fetch #4  8306200B response, 4760631B kept   <- 43% thrown away
fetch #5  4842847B response, 1813644B kept   <- 63% thrown away
```

Two problems in one place:

1. **Roughly 40-60% of every video fetch is discarded**, because a video request also returns
   audio and no track bitfield was found that suppresses it — and the audio track then fetches
   that same audio *again*. A video played this way costs meaningfully more data than it needs
   to. The fix is one shared session feeding both tracks instead of two independent streams.
2. **The bursts are large** — 5-8MB per 10s of media. Fine on wifi, not fine on a metered
   connection, and worth a cap before this leaves beta.

Neither is a correctness bug, which is exactly why they would have gone unnoticed without being
measured. Both are now visible in any report.

## Queue autoplay with the screen off: works. And what the logging found.

Dewi asked for autoplay through the queue to be tested on video and with the screen off, and
for videos not to "finish" early. Tested with the screen genuinely off
(`mWakefulness=Asleep`):

```
[playback] ended at 18944ms of 18933ms — jNQXAC9IVRw "Me at the zoo"
[advance] jNQXAC9IVRw ended -> queue advance=false
[advance] queue empty — playing related "Supernanny VS ..."
[playback] playing at 205770ms
```

**Advance works with the screen off**, and the end is correctly NOT flagged early — the new
`ENDED EARLY at Xms of Yms` line only fires when a finish is more than 5s short of the duration.

### Three bugs the logging found, two fixed

**1. `contentLength` was a RUN's length, not the format's.** Fixed. It reported
`ended at 433081 — 432274B of 807B (53665%)`, 807 being the init segment, which meant the
premature-end guard was comparing against nonsense and could call a stream complete on its
first run. The total now comes from the player response, and a correct finish reads
`433081B of 433081B (100%)`.

**2. A long video stopped at 7%.** `playerTimeMs` only crept forward by one step per fetch, so
on a long video it fell far behind the bytes already served; SABR then answers "you have enough
for that time", which the stream read as the end. It is now derived from the bytes held against
the duration, and never allowed to go backwards.

**3. NOT FIXED — resuming a part-watched video is a SEEK, and seeks do not work.** This is the
one that matters, because it is the ordinary case rather than an edge:

```
fetch #1 itag 137 at 0ms -> 2374047B response, 0B kept
PREMATURE END: itag 137 served 41861347B of 249605762B (16%)
[playback] playing at 367799ms
```

The app resumed at 367799ms from saved progress, so ExoPlayer opened the video track ~41MB in.
SABR is addressed by media TIME, so the bytes it returns start at zero and are discarded as
already-passed, and the video track dies while audio carries on. The video *played* — it just
had no picture.

**So SABR is not used when there is a resume position** — fixed, and verified on-device both
ways. `VideoResolver` asks the progress store before taking the fast path:

```
[resolve] qyPCVqFUyDo resumes at 1166815ms, so extracting rather than using SABR
          — a resume is a seek, and the SABR path cannot seek yet
[resolve] qyPCVqFUyDo in 13973ms for describe — 8 qualities, 8 subtitle tracks
[format]  video video/x-vnd.on2.vp9 1920x1080
```

...and an unwatched video still gets the fast path, with bytes kept and a picture:

```
[sabr]    prepared 9bZkp7q19f0 — audio itag 251 @132kbps, video itag 137 1080p24
[resolve] 9bZkp7q19f0 in 496ms for describe OVER SABR
[sabr]    fetch #3 itag 137 at 20000ms -> 7086017B response, 6761290B kept, 1711ms
[format]  video avc1.640028 1920x1080
```

The trade is explicit: resuming costs the full 14-25s extraction again, which is the price of
it working at all. Seeking on the SABR path is what would remove the trade, and is the next
thing worth doing rather than a nice-to-have.

## Why a SABR video stops around the one-minute mark — measured, not guessed

The premature end survived the `playerTimeMs` fix, so the next step was to log what the empty
response actually **contained** rather than only that it was empty. The line that mattered had
been sitting after an early return, so the one case needing an explanation was the one case that
never got one. Moved, it says this immediately:

```
fetch #7 itag 137 at 61559ms -> 658B response, 0B kept, 24ms, carried nothing
itag 137 got no bytes at 61559ms from 658B:
  parts=[REQUEST_CANCELLATION_POLICY, START_BW_SAMPLING_HINT, LAWNMOWER_POLICY,
         STREAM_PROTECTION_STATUS] itags=[] reasons=[]
PREMATURE END: itag 137 served 23686660B of 103890131B (23%)
```

**No media parts at all** — policy parts and `STREAM_PROTECTION_STATUS`. After roughly a minute
of media the server stops serving and answers with a protection status instead. Reproduced on
four different videos, always after the first ~10-25% and always with the same 658-688B answer.
That is YouTube asking for proof of origin, which needs a PoToken this app does not mint, and it
caps the SABR path at about a minute of playback regardless of anything on our side.

`STREAM_PROTECTION_STATUS`'s fields are logged verbatim and NOT interpreted. Field 1 was assumed
to be a status enum (1 ok / 2 pending / 3 required); on the wire it reads 9000 then 8000, which
look like milliseconds, so the assumption was wrong and a log that had translated it would have
stated a confident falsehood.

### What this changes

The waste figure was wrong too, and in an interesting way. Adding a per-itag tally of each
response shows the audio the video request volunteers **is** the itag we chose:

```
fetch #1 itag 137 -> 4533473B response, 4375729B kept, carried 137=4375729B 251=155762B
fetch #2 itag 137 -> 3352685B response, 1850620B kept, carried 137=3196129B 251=154829B
```

So a shared session between the two tracks would reclaim ~155KB against ~4.3MB — about 4%, not
the 40-60% assumed. The real waste is **re-sent video**: fetch #2 carried 3.19MB of itag 137 and
kept 1.85MB, the rest being bytes already served. Sharing the audio is therefore not worth
building; asking from the right position is.

And the honest summary of the whole path: without a PoToken minter, SABR gives a ~200ms resolve
and about a minute of video. It stays behind the beta flag, and the next thing worth doing is
either minting a PoToken or falling back to extraction mid-stream when the protection status
appears — not more tuning of the request.
