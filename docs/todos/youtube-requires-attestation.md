---
title: YouTube now refuses us past the first megabyte
kind: todo
area: playback
priority: critical
status: open — root cause identified and measured; the fix (a PO token) is not built
updated: 2026-08-18
---

# Nothing streams, and it is not our code

Dewi, 2026-08-18: *"cant play anything that i havent already downloaded"*, then *"ai video not
playing???? why not???? seems like I can't play anything???"*.

He was right, and it is not a regression we introduced. Nothing in the app changed between the
evening it worked and the morning it did not.

## What was measured

From his own IP, on his own videos, on freshly-obtained URLs:

| Route | Direct URL? | Fetchable past ~1MB? |
|---|---|---|
| `android_vr` — yt-dlp 2026.07.04's **only** default YouTube client | yes | **no.** `0-1048575` → 206, anything beyond → 403 |
| `android`, `tv`, `web`, `web_safari`, `ios`, `mweb` | **no URL at all** — SABR-only formats | — |
| `web_embedded` **with** a JS runtime | yes, with a deciphered `n` | only sometimes — 1 of 3 videos tried |
| SABR (`serverAbrStreamingUrl`) | n/a | **no.** ~812KB, six segments, then it stops |

The ceiling is the same on both routes, which is what makes it one cause rather than two bugs. And
the SABR responses say so outright — the server sends media *and* a refusal in the same answer:

```
server sent parts=[REQUEST_CANCELLATION_POLICY, START_BW_SAMPLING_HINT, LAWNMOWER_POLICY,
                   SABR_ERROR, STREAM_PROTECTION_STATUS, SELECTABLE_FORMATS,
                   MEDIA_HEADER, MEDIA, MEDIA_END]
```

`STREAM_PROTECTION_STATUS` is the attestation signal. The app holds no PO token and has no way to
produce one, so it gets a trial window — about a megabyte, about a minute — and then is turned away.

**SmartTube on his TV, on the same broadband, plays fine.** That is the decisive comparison: it is
not the address being refused, it is *this client* not attesting. Dewi is the one who pointed it out,
and it reframed the whole investigation — up to that point the working theory was our chunk size.

## What was fixed on the way (all shipped)

None of these make streaming work. They were all real, and three of them are the same shape.

- **A refused download never tried the working route.** `shouldFallBack = { it.isPermanent }` — a 403
  is transient, so the second route was withheld from exactly the failure it exists for. Now
  `DownloadState.Failed.deservesAnotherRoute`, a named rule with unit tests instead of a lambda at
  the wiring. See [failure-handling.md](../features/failure-handling.md).
- **SABR's claimed position ran away from its data.** Floored at `playerTimeMs + stepMs`, so it
  gained ten seconds per fetch whatever arrived; once ahead, the server says "you have enough", which
  was read as empty and punished with a thirty-second skip. 793KB of Opus believing it was 160
  seconds in.
- **The empty-response budget was a lifetime count** that nothing reset, so the fourth blank answer
  of a session ended the stream however healthy the hundreds of fetches around it.
- **`buffered_ranges` was never sent**, so the server had no idea what we held and re-sent it: 52% of
  every byte fetched discarded. Now built from each `MEDIA_HEADER`'s `sequence_number`, with the time
  span derived from bytes because live headers carry no `start_ms` at all.

### The pattern worth remembering

**Four gated fallbacks, each a working second route behind a condition the real failure does not
trigger:**

| Where | Its gate | Why it never fired |
|---|---|---|
| `FallbackDownloadStrategy` | `isPermanent` | a 403 is transient |
| `VideoResolver`'s player fallback | extraction failed | extraction succeeded — the *stream* failed |
| `StreamRecovery` | budget spent | spent re-resolving the same dead route |
| `InnerTubePlayerStreams`' account path | anonymous call refused | anonymous call succeeded, then was refused mid-stream |

Every one of them was written after a real incident, tested, and correct about the case it was built
for. Together they meant an app with four fallbacks and no working path.

## A partial fix that works: prefer the URLs that have been through the `n` solve

Shipped 2026-08-18, and it should restore most streaming.

`web_embedded` returns `c=WEB_EMBEDDED_PLAYER` URLs carrying a **deciphered `n`** — the parameter a
JavaScript runtime exists to solve — and those serve the whole file. The app already runs QuickJS for
extraction on purpose, so these were available all along and simply never asked for: `player_client`
listed `default` and `android`, and neither yields them.

Asking for the client is not enough on its own. With all three requested, yt-dlp's own `bestaudio`
still returns `ANDROID_VR`: it is the largest, in the right language, and unfetchable past a megabyte.
Durability is not something a bitrate can express, so `MediaFormat.isDurable` is now the **first** key
in the app's own picker, ahead of language and size.

Measured on the NASA fixture with all three clients requested:

```
audio-only formats with a URL: 37
by client:  WEB_EMBEDDED_PLAYER 33, ANDROID_VR 4
durable:    33 yes, 4 no
best durable: itag 140-0, 35644394 bytes
  first 256K   206      middle 206      near the end 206
  a 2MB chunk (what ChunkedDataSource asks for) 206
```

**Proven on the device, and that mattered.** The measurements above were taken on a laptop using
**node**; the app ships **QuickJS**, a different engine on a different architecture, and a fix that
only works with node would have been a no-op on the phone with a green JVM suite —
`DurableUrlsOnDeviceTest` runs the real interpreter on a real device and asserts a deciphered `n` comes
back. It passes.

It also cost an hour of false alarm worth recording: written first in `:lib:ytdlp-chaquopy`, it failed
saying QuickJS had produced nothing, which read as "the fix does not work on the phone". It was the
test that was wrong. `libqjs.so` is bundled in **`:app`'s** `jniLibs`, so that module's own test APK
cannot contain it — the runtime was absent and yt-dlp fell back to the client with no `n` to solve. A
test in the wrong module measures a different app.

**And it plays, end to end, on a device.** `LiveStreamPlaysToItsEndTest` played a real YouTube stream
on the emulator, seeked to `duration − 6s` — deep into the file, the exact region that was answering
403 — and reached the end. 85 seconds, 0 failures, **0 skipped**. The same test *skipped* in CI the
night before with "YouTube did not serve this machine a playable stream", which is what makes the pair
of runs evidence rather than a single green tick.

Worth being blunt about the order this was established in: URL-level `curl` measurements and an
extraction test came first, and neither is playback. Dewi asked "did you test on a emulator??" and the
honest answer at that moment was "not the part that matters". The end-to-end run came after the
question.

**Still partial.** One of the three videos tried was refused even via `web_embedded`, so some items
will remain unplayable until the real fix lands. `sR1s-pxRktU` and `ttiLcMUQq80` were durable;
`ng2Tsa5KE_A` was not.

## Watching a long video: there is nothing durable to choose, so it keeps the sound

The durable-stream preference fixed **listening** and left **watching** broken, and the reason is not
a bug we can pick our way out of. Measured on NASA's 97-minute "Cosmic Dawn", across every client
yt-dlp can reach:

```
VIDEO formats with a URL: 19    all ANDROID_VR, durable: 0
AUDIO formats with a URL: 77    73 durable (WEB_EMBEDDED_PLAYER)
```

**No video format carries a solved `n`.** YouTube serves video only over SABR to the clients that
would attest, so the quality ladder has nothing durable to offer and an hour-deep seek while watching
cannot be made to work by choosing better. It failed roughly 7 times in 10 while the audio-only URL
served the same byte offset 5 times out of 5.

So recovery gained a rung: **keep the sound when the picture is refused.** After a copy on disk (no
data, cannot stall) and before abandoning the item. It is the streaming twin of the download rule Dewi
settled on 2026-08-14 — once every retry has failed the choice is "audio or nothing", and skipping is
the worse answer.

That exposed a real pre-existing bug on the way. `listen()` passed no start position, so it began again
at zero while its own documentation claimed it "replays from the saved position". Mild when toggling
Listen on a short video; fatal here, because the rescue of an hour-deep seek threw the hour away:

```
playback: keeping the sound without the picture — the video stream will not serve
playback: ready after 395ms at 10ms
playback: playing at 11ms
```

### Measured on the emulator, hour deep into a 97-minute video

| State | Video mode | Listen mode |
|---|---|---|
| Before any of this | 0 / 3 | — |
| Durable preference only | ~3 / 10 | 4 / 4 |
| **+ sound fallback that keeps its place** | **4 / 4** | **4 / 4** |

Full live set on `totum-api35`: **5 tests, 0 failures, 0 skipped.**

## The plan to get the picture back, and why SmartTube is the proof

Dewi, 2026-08-18: *"you plan to get this all working??? they work great in smarttube"*. Fair, and it is
the most useful constraint in this document: **a client on the same broadband, on the same account,
plays these videos in full.** So the working configuration is not something to discover — it is
something this app already half-implements and never reaches.

SmartTube is a **signed-in TV client playing over SABR**. Both halves exist here:

1. **The signed-in TV `/player` call** — `InnerTubePlayerStreams`' account path. Its own comment records
   the decisive measurement: the *current* TV client "withholds all but ONE stream (SABR)" while the
   *downgraded* one returns seven direct URLs. That one SABR stream is exactly what SmartTube consumes.
   The code prefers the downgraded client to avoid watching an age-restricted video at 360p — sound
   reasoning for the case it was written for, and it routes around the very thing now needed.
2. **A SABR reader** — `:lib:sabr`, which parses UMP, attributes runs by header id, and (since today)
   tracks its claimed position honestly, refills its empty-response budget, and sends `buffered_ranges`.

### The steps

1. **Ask the account first when signed in.** Done — `preferAccount` on `InnerTubePlayerStreams`, wired
   to `accountSubscriptions.signedIn`. A no-op signed out, so nothing changes for anyone until there is
   an account. This is the gate that never fired: it rescued only a *refused* anonymous call, and the
   anonymous call succeeds and is then stripped of its video URLs.
2. **Prefer the CURRENT TV client, not the downgraded one, when the goal is SABR.** Not done, and not to
   be done blind: it is the difference between seven direct URLs and one SABR stream, and getting it
   wrong regresses age-restricted playback to 360p. It needs measuring against a signed-in session.
3. **Play that stream over SABR** — `sabrEnabled` already routes there, and the label's "stops after
   about a minute" was the runaway claim and the lifetime empty-budget, both fixed today.
4. **Verify at breadth**, with the tests that now exist: hour-deep seek, Ms Rachel, live, 4K, subtitles.

### The one thing blocking it

**A signed-in emulator, which needs a human to approve one device code.** Google refuses an automated
browser outright — *"Couldn't sign you in. This browser or app may not be secure."* — which is
self-consistent, since the TV device-code flow exists precisely because Google blocks embedded logins.
Attempted from a copy of the signed-in automation profile: the profile was signed in to Google, the
code was accepted, and it died at the account chooser because that account showed as signed out.

So step 2 is measurable the moment somebody types a code, and guessing at it beforehand is how the two
disproved theories of this morning were born.

## The full fix if the above fails, and why it is not built

**A PO token.** yt-dlp reaches them through an external provider; SmartTube runs the attestation
itself. The app already bundles QuickJS, so running BotGuard is not obviously out of reach — but it
is a new capability, not a flag, and building it on a hunch is how the last two theories died
(chunk size, then buffered ranges — both wrong, both measured wrong before being believed).

**The most promising lead, unverified.** The signed-in path in `InnerTubePlayerStreams` already uses
a **TV context**, which is what SmartTube is. It is the fourth row of the table above: consulted only
when the anonymous call is refused, which today it is not. If a signed-in TV `/player` response yields
a SABR session that serves in full, the fix is small and the seam already exists. It could not be
tested here — the emulator's token was not readable and a JVM test has no account — so it is written
down rather than guessed at. **Test it on a signed-in device before building anything on it.**

## Measured across content types on the emulator (2026-08-18)

`PlaysAcrossContentTypesTest` plays four shapes in VIDEO mode with auto-download disabled, and asserts
the one thing the app can promise: **the position advances** — sound, not merely `isPlaying`, since a
player stuck at one millisecond reports playing.

| Content | Sound | Picture | What happened |
|---|---|---|---|
| 19-second clip (`jNQXAC9IVRw`) | ✅ | ✗ | only 144p/240p on offer and the 240p stream was refused, so the sound fallback carried it |
| 97-minute VOD (`uSMGENDH_QI`) | ✅ | ✅ | 480p vp9 merged, `hasVideo=true`, from the start |
| Ms Rachel, made-for-kids (`gngPQ771Ahk`) | ✅ | ✗ | went to audio; kids content has been served degraded since 2026-07-30 |
| Live stream (`YDvsBbKfLPA`) | ✅ | ✅ | 480p avc1 single stream, `hasVideo=true` |

**Sound is not guaranteed either, and that correction matters.** A later run had the 97-minute VOD go
completely silent: `route -> refused: the stream will not play and there is no copy on disk`. In a
SABR-only session YouTube strips the direct URLs **including the audio-only one**, so there is no stream
AND nothing for the sound-only fallback to reach. The earlier "sound on all four" was true of that run,
not a promise. Both live tests now count silence against the app only when nothing explains it — a
`refused` route is YouTube declining, and asserting otherwise would be the same
test-someone-else's-policy mistake for the fourth time.

That is also the strongest argument for the SmartTube path below: in exactly those sessions, SABR is
what YouTube IS offering.

**There is no systemic picture bug** — the VOD and the live stream kept theirs in the same run, so
whether a picture survives is a property of what YouTube serves for that video, not of the app's mode
handling.

Two measurement traps had to be removed before this table meant anything, both of which produced a
confident "no picture" that was false:

- **`hasVideo` was sampled, not awaited.** It comes from the decoder's track list, which arrives after
  playback starts, so a plain muxed clip read as having no picture.
- **The queue auto-downloads what you enqueue.** `playNow` adds the item, the queue fetches its audio,
  and once a stream is refused `routeNow` rightly prefers that fresh local copy — so "no picture" meant
  "we had a download". Correct behaviour, useless as evidence. The test disables auto-download and
  restores the setting afterwards.

## yt-dlp says it in words, and we were silencing it

The bridge set `no_warnings: True`, so the one account of a degraded extraction was thrown away. With a
collecting logger in its place, the device says:

```
warning: Some android client https formats have been skipped as they are missing a URL.
         YouTube may have enabled the SABR-only streaming experiment for the current session.
         See https://github.com/yt-dlp/yt-dlp/issues/12482
info:    [jsc:quickjs] Solving JS challenges using quickjs
```

Two things settled at once. **QuickJS is working** — it is solving the challenges, so the missing durable
video is not our runtime failing. And the cause is YouTube's **SABR-only experiment, enabled per
session**, which strips URLs from formats. That is why the same video produced 33 durable audio formats
on one run of this emulator and none on the next, and why an assertion that durable formats exist went
red having passed twice.

`ExtractionResult.Success` now carries those notes and the engine logs them, so a report from Dewi's
phone can say whether a silent item was YouTube withholding or us breaking. It is the difference between
a diagnosis and a guess, and it cost a day to be without it.

## What a test may assert, and what only a canary can

Worth stating plainly, because getting it wrong nearly recreated the original failure. The first version
of `SabrCarriesAWholeStreamTest` demanded 80% of a stream — a fair description of what the app needs, and
impossible until a PO token exists. That is a build red every run for a reason nobody in this repository
can fix, and a permanently red build is exactly what taught everyone to wave the earlier
`assumeTrue("… not this defect")` skip through. A test that lies by being silent and a test that lies by
shouting are the same bug.

| Question | Belongs to |
|---|---|
| Does our machinery still work on what we are given? | a **test** — red means our bug |
| Has YouTube's policy changed? | the **canary** — hourly, reports state changes |

So the test asserts the floor we own: SABR delivers the window it *is* offered, and when it stops the
response carried a refusal. An early ending with no refusal in it is ours to explain. The 80% belongs
here, in prose, with its date — and when the ceiling lifts the canary is what says so, and the floor gets
raised then.

## What now guards this

- `SabrCarriesAWholeStreamTest` (`:app`, JVM, live) — fetches real bytes and requires **80% of a
  37-minute video**. Fails right now, correctly. Runs through the home connection, and a failure
  turns CI red rather than printing "SKIPPED".
- `tools/ci/youtube-canary.py` — hourly on the Pi, range-fetches 1MB from 8MB into a real stream and
  reports **state changes only**. It says `capped` today, which is the expected state.

  Its states are `open` / `capped` and describe **YouTube's policy, not whether the app works.** The Pi
  has no JavaScript runtime, so its `yt-dlp` only ever obtains the unattested (`c=ANDROID_VR`) URLs —
  precisely the ones subject to the cap — while the app runs QuickJS and prefers a solved `n`. The two
  can therefore disagree, and that is correct. The first version called the states "working" and
  "broken" and announced *"Totum/YouTube BROKEN"* on the day the app had just been fixed; a monitor whose
  wording implies the wrong subject teaches you to distrust something that is fine.

  Watching the app's OWN path would need a JS runtime on the Pi. There is no `quickjs` package for it and
  `nodejs` is 184MB installed, so that is Dewi's call rather than something to slip in. This is the piece that would have caught it
  the same day: no commit caused this, so nothing that runs on push could have.
- The three live tests that existed could not have caught it. They asked for **1 second** of
  playback, a **10KB** file, and used a **19-second** fixture whose entire download fits under the
  cap. See [tests/_index.md](../tests/_index.md).

## The 4K60 gap, measured rather than assumed (2026-08-18)

Dewi: *"you plan to get this all working??? they work great in smarttube"*. Here is exactly what
separates us from SmartTube, probed live today rather than reasoned about.

**SmartTube's advantage is its client identity, not its SABR code.** Asking `/player` anonymously:

| Client | Status | Formats | With a URL | 60fps | Above 1080p |
|---|---|---|---|---|---|
| `ANDROID` 20.10.38 | OK | 34 | 34 | 10 | 4 (to 2160p) |
| `TVHTML5` 7.20240401 (downgraded) | **LOGIN_REQUIRED** | 0 | – | – | – |
| `TVHTML5` 7.20250312 (current) | **LOGIN_REQUIRED** | 0 | – | – | – |

So the TV client is not available to us anonymously at either version — that is now measured, not
assumed, and it is why the account matters beyond age-restricted videos. `preferAccount` (this
commit's parent) makes the app ask as the account first when there is one; without one there is no TV
client to ask as.

**And the SABR caps are still correct today.** `SabrServesWhatWeChooseTest`, live:

```
[sabr] our own picks: audio 474KB, video 115KB
[sabr] confirmed still refused: itag 401 2160p60 served 0KB. Our caps remain correct.
```

YouTube lists 2160p60 to the ANDROID client and then serves zero bytes of it over SABR. So the
1080p30 cap is not our conservatism, and raising it would break playback rather than improve it.

**What is left needs one thing only a human can do.** Sign in on the emulator: Google refuses an
automated browser outright (*"This browser or app may not be secure"*), so somebody has to type a
device code at google.com/device once. With a signed-in session the remaining experiment is a single
measurement — does the CURRENT TV client's `ustreamer_config` serve the 60fps and 2160p formats the
ANDROID one refuses? If yes, that is 4K60 and the gap closes. If no, SmartTube's quality comes from
somewhere else and this doc is wrong about the cause, which is worth knowing either way.

Until then the honest position is: **1080p30 over SABR, full quality whenever direct URLs are
available**, and no guessing in between.

## 4K, settled (2026-08-18)

Dewi: *"also make sure it works at 4k"*. Three separate questions, measured on `totum-api35` rather
than reasoned about:

| Question | Answer |
|---|---|
| Does YouTube offer 4K? | **Yes** — `itag 315` and `401` at 2160p60, with URLs, on Blender's CC film |
| Does the app ask for it? | **Not by default** — `DEFAULT_WIFI_MAX_HEIGHT` is 1080. 2160p is selectable in Settings |
| Does it work when asked for? | **Chosen and decoded, but does not sustain** |

With the cap raised the app picks it correctly and the decoder runs:

```
[4k] the ladder chose 2160p (durable video=false)
[format] video video/x-vnd.on2.vp9 3840x2160
```

But `durable video=false`: there is **no durable 2160p URL**, so the stream is refused past its first
megabyte, and SABR cannot stand in above 1080p/30fps. Sustained 4K is therefore behind the same
attestation wall as everything else here, not behind a quality-selection bug — and the ladder's
durable-first sort is not at fault, because it sorts *within* a height group and found no durable
option at 2160p to prefer.

`FourKActuallyPlaysTest` asserts the half that is ours — raising the cap must raise the pick above
1080 — on **both** paths, so a regression that stopped the setting reaching the ladder cannot hide
behind YouTube refusing the stream.

**Open for Dewi:** whether to raise the Wi-Fi default to 2160. Deliberately not changed: a 1080p phone
screen gains nothing from 4K and spends roughly four times the data getting there, and today it would
not sustain anyway.

## Correction: what the extraction notes actually contain (2026-08-18, later)

An earlier section here recorded the expected note set as one `warning:` plus one `[jsc:…]` line and no
`[youtube] Downloading …` lines. That was wrong, and it described the bug rather than the behaviour.

yt-dlp's `YoutubeDL.to_screen` routes **every** routine progress line through `logger.debug()` **without**
a `[debug] ` prefix (the `quiet` check sits on the line below the early return), while `write_debug`
returns early unless `verbose` is set, which extraction never sets. So the collector's "drop anything
prefixed `[debug]`" filter excluded nothing and kept precisely what it claimed to drop: nine notes on a
healthy extraction, two of them real, and a ~1KB WARN on every single resolve.

`notes` now carries **warnings and errors only**, with a counted trailer so a truncation cannot be
silent, and `lib/ytdlp-chaquopy/src/test/python/totum_ytdlp_test.py` pins it against the real transcript.
A healthy extraction now produces **no notes at all**, which is what makes the line meaningful when one
does appear.
