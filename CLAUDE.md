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

## Decisions (agreed with Dewi, July 2026)

| Decision | Choice | Why |
|---|---|---|
| Scope | Both pillars from day one | Unified data model proven early |
| Stack | Kotlin + Jetpack Compose, single Gradle project | Native fit, strongest type safety |
| minSdk | **34** (Android 14) | Personal modern devices; simplifies stack. Deliberate — drops the API-23 floor of the original apps |
| Extraction | **From-scratch library in this repo** (`:lib:ytdlp`), replacing dewijones92/youtubedl-android fork | Own a clean, tested Kotlin API around the real yt-dlp |
| Python runtime | Chaquopy 17 (revised from "official CPython" once that proved tier-3/no artifact) | Mature drop-in embed, MIT, pip support |
| ffmpeg | **Bundled** — minimal static build in jniLibs | Merges best-quality DASH streams and removes SponsorBlock from downloads |
| CI/CD | GitHub Actions; signed APKs on GitHub Releases | No Play Store (yt-dlp app) |
| YouTube account (July 2026) | Own minimal InnerTube client (`:lib:innertube`) + **TV device-code OAuth**, SmartTube-style; yt-dlp stays for extraction/playback | Signed-in features (subs, history, comments, likes) need auth + writes; yt-dlp is read-only and removed OAuth login; Google blocks WebView logins, and the device flow is the login it expects from TVs |
| UI bar | Genuinely nice, modern | Material 3 expressive, dark/light, edge-to-edge, considered motion — never template-default |
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
  lazy (constructed on first Videos/Search use, never at launch) — confirmed:
  startup pays nothing for it. Keep it that way.
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
./gradlew connectedDebugAndroidTest  # instrumented tests (device/emulator needed)
```

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
  `adb shell am instrument -w -r -e class <FQN> com.dewijones92.totum.test/androidx.test.runner.AndroidJUnitRunner`
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

- `:app` — Compose UI: `AppShell` bottom navigation across the pillars
  (Videos / Podcasts / Library), theme, screens.
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
  ffmpeg, then cuts SponsorBlock segments), `HttpDownloadStrategy` for podcast
  enclosures (a plain HTTP GET). A third pillar cannot be added without that
  `when` failing to compile. `DownloadState` in `:core:domain`; playback
  prefers the local file wherever one exists — decided by `routeNow` below, for
  both pillars, so a downloaded video needs no re-resolution. Interrupted downloads (a `Downloading` row at
  startup) are dropped — an absent record already means NotDownloaded.
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
  ModifyChapters postprocessor — the one that cuts SponsorBlock segments out of
  a download — asks ffprobe for the media duration and fails with "ffprobe not
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
  controls that one session. `POST_NOTIFICATIONS` is requested at first play
  (`RequestNotificationPermissionOnFirstPlay`) — required on API 33+ or the
  notification never shows. `fake.FakePlaybackController` for tests/previews.
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
- `:lib:common` — pure-Kotlin utility module with no app dependencies, shared
  by app modules and standalone libraries alike (it would be published
  alongside `:lib:ytdlp`, like the old youtubedl-android's `common` module).
  Home of `HttpUrl`, the single validated URL type used everywhere.
- detekt, the Android lint policy, and Android build defaults
  (compileSdk/minSdk/Java level) apply to every module automatically from the
  root build — never configure them per module.
- Package root: `com.dewijones92.totum`.

## Working agreements

- Commit as you go — small, coherent commits at each green state.
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
  it: the tip is a superset. Learned 2026-07-25, at the cost of the Totum APK. Release signing key lives in
  three places, never this repo: locally at `/home/dewi/code/dewiuniapp-signing/`,
  in this repo's Actions secrets (CI signing; write-only), and backed up in
  the PRIVATE repo `dewijones92/uniapp-signing-backup` (survives laptop
  loss). versionCode is `100 + run number`, so it only ever increases.
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
