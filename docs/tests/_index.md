---
title: Testing
kind: reference
updated: 2026-08-31
---

# Testing

Testing pyramid: many fast JVM unit tests, fewer integration, few
instrumented/UI. **New behaviour lands with tests.** 492 JVM `*Test.kt` files and 82
instrumented ones, counted 2026-08-20 — the long-standing "~50 unit, ~5 instrumented" here was
an order of magnitude out.

## The gate (matches CI)

```bash
./gradlew detekt lint test koverVerify        # full local gate
./gradlew connectedDebugAndroidTest           # instrumented (device/emulator)
```

`koverVerify` covers `:core:*` / `:lib:*` **except** the kover-exempt adapters
`:core:database`, `:core:playback`, `:lib:ytdlp-chaquopy` (instrumented-verified
instead).

## Where coverage lives

| Area | Kind | Notes |
|---|---|---|
| RSS parse, chapters, import/export | JVM unit | `:core:data` — the untrusted-input hot spot |
| Search (sources, history), content refresher | JVM unit | `:core:data` |
| Local playlists, play history | JVM unit | `:core:data` (in-memory store contracts) |
| Downloads (routed/engine/http strategies) | JVM unit | `:core:data` |
| Cancelling and retrying a download | JVM unit | `:core:data` `CancelAndRetryTest` — incl. delete-in-flight, which used to write itself back |
| Download sort orders incl. by size | JVM unit | `:app` `DownloadSortTest` — caught a companion-init bug that put a null in the menu |
| The Library's download management | JVM unit + instrumented | `:app` `DownloadManagementTest`, `DownloadRowStatesTest` |
| Pillar inference, `fetchUrl`, `DownloadedMedia.offline` | JVM unit | `:core:domain` — the rules that used to exist twice |
| Download-record migration (v13→v14 backfill + table shape) | instrumented | `:core:database` — real files must survive it |
| InnerTube parsers (feeds/related/comments/search/…) | JVM unit | `:lib:innertube`, against captured fixtures |
| yt-dlp `BridgeJson` | JVM unit | `:lib:ytdlp` — the parser is platform-neutral and lives beside the API, not with the Chaquopy engine (this row said `:lib:ytdlp-chaquopy` until 2026-08-19) |
| yt-dlp's notes reaching EVERY front end | JVM unit | `:lib:ytdlp` `EveryFrontEndHearsTheNotesTest` — reported where the contract is parsed, so the CLI cannot silently lose them |
| The bridge's JSON envelope, success and failure | python | `lib/ytdlp-chaquopy/src/test/python` `FailedExtractionNotesTest` — imports the real bridge against a stub yt-dlp; a failed extraction used to bin the warnings explaining it |
| Ending playback in a test fake is never a silent no-op | JVM unit | `:core:playback` `EndingNothingIsATestBugTest` — a swallowed signal is indistinguishable from a product bug |
| The `/player` ladder keeping your audio track | JVM unit | `:app` `ASecondOpinionRungKeepsYourTrackTest` — the rule lived on the yt-dlp ladder only, so the degraded path served the wrong language |
| Room DAOs / stores | instrumented | `:core:database` |
| `Media3PlaybackController` / service | instrumented + on-device | `:core:playback` |
| Ranged fetch arithmetic + stopping rule (`ChunkedRead`) | JVM unit | `:core:playback` — 18 cases; the class every stream flows through, previously untested |
| `ChunkedDataSource` over a googlevideo-shaped stand-in | instrumented | `:core:playback` — no network; resumed reads, past-the-end ranges, truncated resources |
| An item resumed near its end reaches its end | instrumented | `:app` `StreamPlaysToItsEndTest` — real player over a localhost ranged server |
| The same against a real YouTube stream | instrumented, live | `:app` `LiveStreamPlaysToItsEndTest` — via `tools/ci/live-test-via-home.sh`, allowed to skip. **Neither of these reproduces the reported stall** — see below |
| ViewModels, queue | JVM unit | `:app` |
| Picking a colour from artwork | JVM unit | `:app` `ArtworkColourTest` — the traps that make naive versions produce mud |
| The shared row keeping every action through a restyle | instrumented | `:app` `MediaItemRowKeepsActionsTest` — ten screens depend on it |
| The player keeping every control through a redesign | instrumented | `:app` `PlayerKeepsEveryControlTest` — written BEFORE the redesign, which is the whole point |
| Search results streaming per section | JVM unit | `:app` `SearchStreamsPerSectionTest` — incl. the reported case, a slow torrent search not blocking YouTube |
| What each search section state looks like | instrumented | `:app` `SearchSectionStatesTest` |
| The facts under every video title (channel, views, date — one per line) | JVM unit | `:app` `MediaItemSubtitleTest` — testable at all only because `@Composable` came off the formatter |
| What a resolution may change about an item | JVM unit | `:core:domain` `WithStreamFromTest` — the rule that stops views/dates being destroyed at play time |
| Views + dates on a **page-2** feed video | JVM unit | `:app` `VideosPagingTest` — where "scrolled down" can actually break |
| Views + dates crossing the media session | instrumented | `:app` `PlayerMetadataTest` — extras written but never read compile fine and deliver nothing |
| Views + dates on a row 60 deep, the last row, and one scrolled back into view | instrumented | `:app` `ScrolledRowMetadataTest` |
| A queue drag continuing the same item across swaps, reversals, and the list resizing | JVM unit | `:app` `ReorderStateTest` |
| A queue drag surviving the list changing SIZE under it | instrumented | `:app` `ReorderAutoScrollTest` — `itemCount` was the gesture's second key; watched failing at 0 moves |
| A queue drag surviving its own swaps | instrumented | `:app` `ReorderAutoScrollTest` — needs composition to settle BETWEEN pointer events; a frozen clock (every other case there) cannot see it, which is why a one-place-only drag shipped |
| A queue drag of ten places in one motion | JVM unit | `:app` `ReorderStateTest` — the accumulator over distance. Deliberately NOT a gesture: that version failed on CI twice on screen size and frame timing while passing locally |
| Views + dates surviving the **database** | instrumented | `:core:database` `ItemFactsSurviveStorageTest` — queue and history; the boundary where they were dying |

| SABR carrying every content type it can | instrumented, live | `:app` `SabrPlaysAcrossVideoTypesTest` — five shapes through the real `SabrResolve` seam; 1080p30 VOD, 1080p25 kids, 480p30 on a 4K60 upload, live refused by name |
| How far SABR actually serves | instrumented, live | `:app` `SabrKeepsServingTest` — **bytes, not position**: this emulator's software decoder cannot hold 1080p, so a position bar would measure the machine. Measured 11.3MB in 6 fetches |
| Which formats SABR will really serve | instrumented, live | `:app` `WhatSabrWillServeTest` — re-measures the 60fps/webm refusals rather than trusting the recorded table, and is how live's missing `lastModified` was found |
| A live stream is refused, a VOD without `lastModified` is not | JVM unit | `:app` `ALiveStreamIsNotRefusedBySabrTest` — both halves, so relaxing one gate cannot silently hand live to a path that fetches and never plays |
| A SABR-only response surviving the gate | JVM unit | `:app` `InnerTubePlayerStreamsTest` — the response with no direct URLs is the one SABR exists for, and it was being discarded before SABR was asked |
| The queue fetching its own audio | instrumented, live | `:app` `AutoDownloadFetchesTheAudioTest` — queue it, touch nothing, find audio-ONLY on disk. The loop had unit tests; nothing proved the parts agree on a device |

## Verification reflexes (learned the hard way)

- **Verify on a device, not just the JVM.** The podcast RSS bug (Android's Expat
  parser rejecting `DocumentBuilder` bean toggles) passed every JVM test and only
  surfaced on the emulator. Same for the Cast crash (only when the full player
  opened) and the queue being inert in the mini player.
- **Check the source of truth**, not the surface: read the DB / prefs / session
  state (`dumpsys media_session`, SharedPrefs) after driving the UI.
- **kotlinx JSON present-null gotcha:** `obj["k"]?.jsonArray` throws on a JSON
  `null`; always `(obj["k"] as? JsonArray)`. Cover parser paths with a
  null/missing-key fixture.
- **Never reimplement the rule in the test.** Copying a decision into a test so it can stay a
  pure unit test makes a second implementation, and it is the *copy* that gets asserted. On
  2026-08-17 `PreloadOnWifiOnlyTest` held its own version of "which stream will play", pinned the
  wrong answer, and four green tests certified a bug the app had been logging on every item for two
  weeks (`preloadsWasted = 12` of 12). If the real rule needs Android to reach, extract it as a pure
  function and test *that* — do not retype it.
- **A threshold is a claim about what "working" means — make it one that can fail.** The three live
  YouTube tests all passed on 2026-08-18 while nothing in the app would play, because they asked for
  **1 second** of playback (`SabrPlaybackTest`), a **10KB** file (`LiveSabrDownloadTest`), and used a
  **19-second** fixture whose entire download fits inside the cap that had just been imposed. A bar
  that low cannot tell working from broken. `SabrCarriesAWholeStreamTest` asks for 80% of a 37-minute
  video instead, as a proportion of the stream's own declared length so it cannot rot.
- **Some breakage arrives without a commit, and no push-triggered test can catch it in time.** YouTube
  changed; the app did not. That needs a clock, not a pipeline: `tools/ci/youtube-canary.py` runs
  hourly on the Pi and reports state changes. See
  [youtube-requires-attestation](../todos/youtube-requires-attestation.md).
- **Live tests share one device, and the state they share is bigger than you think.** On 2026-08-18
  three separate contaminations surfaced within an hour of adding one live test:
  `SeekDeepIntoALongVideoTest` left a **97-minute download running** (`playNow` queues an item and the
  queue auto-downloads it); `LiveDownloadedVideoOfflineTest` left the **radios off**; and it leaves a
  **downloaded copy of the same video id** `LiveStreamPlaysToItsEndTest` streams, so recovery correctly
  played the file and a streaming test stopped testing streaming. Each passed alone. Each fix is a
  precondition — establish the network, delete the fixture's copy, cancel the download — never a
  loosened assertion.
- **Passes alone, fails in the suite ⇒ shared SAMPLED state, and confirm before calling it flaky.**
  `MeteredAudioSwitchDeviceTest`'s blip case failed only after its sibling (2026-08-17). `@Before`
  reset the visible setting, but `MeteredAudioSwitch` accumulates metered time on a **5-second
  sampler** and zeroes it only on a tick that *observes* an unmetered network — so the sibling's
  deliberately-banked 15 seconds carried, and the 6-second blip fired instantly. Running the class
  in isolation (green, 2/2, 70 seconds) is what told ordering apart from a real regression. The fix
  establishes the precondition (`awaitRearmed()`) rather than loosening the assertion.

## Adversarial audit

The whole codebase can be swept with a fan-out audit workflow
(find → multi-lens verify → synthesize). The targeted version has repeatedly
found real HIGH bugs; keep it in the toolkit for pre-release hardening.

## The core loop, on a device (2026-08-01)

`app/src/androidTest/…/playback/AutoAdvanceLoopTest` is the only test that exercises invariant
I1 — *when an item finishes, the next one starts* — with a real `ExoPlayer` reaching the real
end of real media. It exists because all three autoplay bugs of the previous week passed the
JVM suite: each component was correct against its fake, and the composition was not.

It plays a one-second silent WAV, generated at run time rather than committed, through the app's
own container and the `PlayHandle.Podcast` route — a local file handed straight to the
controller, so a failure is a failure of the loop and not of YouTube. Two cases: an item
finishing starts the next, and an item played a SECOND time advances again (report 0.1.258).

Run it with:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dewijones92.totum.playback.AutoAdvanceLoopTest
```

**The screen must be on and unlocked.** Android denies audio focus to a background app, and a
suppressed player never ends, so the test would be measuring nothing. It says so when it fails.

## Live-YouTube tests run through home broadband (2026-08-01)

`SabrPlaybackTest` talks to the live service, and a GitHub runner is a datacentre IP that gets
bot-checked — its first CI run came back `Unplayable`. It now runs through a WireGuard peer on
Dewi's Pi (dot-files `vpn-stack`, `wg-home`), so YouTube sees a residential IP, driven by
[`tools/ci/live-test-via-home.sh`](../../tools/ci/live-test-via-home.sh).

Three things about it are deliberate:

- **The peer can only reach the internet.** `wg-home` clients normally get full LAN access, and
  this key lives in a public repo's secrets. The Pi firewalls it
  (`vpn-stack/wg-home-init/10-ci-peer-lockdown.sh`), and the script **asserts** both that egress
  is the expected residential IP and that two LAN hosts are unreachable — failing the build if
  the lockdown ever stops holding, rather than proceeding quietly.
- **Neither the IP nor the key is ever printed.** This repo is public, so its logs are; the
  expected IP is itself a secret and only a verdict is logged.
- **It says whether it RAN or skipped.** "Finished 1 tests" and "BUILD SUCCESSFUL" look
  identical either way, so the one question the tunnel exists to answer was unanswerable from
  its own output until the script read the result XML and said which.

It runs inside the emulator action's `script`, because that action kills the emulator the moment
the script returns — and as a file, because it rewrites backslash line-continuations inside
`script:` (its own log shows `sh -c \yes | sdkmanager`), which turned a wrapped gradle command
into a task named backslash.

## Test the wiring, not just the part (2026-08-01)

The recovery fix shipped with two tests of `VideoResolver.forget` — and neither covered the bug.
Removing the one line in `PlaybackQueue.replayCurrent` that calls it leaves both green while the
defect returns in full. Demonstrated, not argued: with the fix reverted, 39 tests ran and exactly
one failed, and it was the new one.

This is the same shape that let three autoplay bugs ship in a week. A component tested against a
fake proves the component; it says nothing about whether anything calls it, and "nothing calls
it" is the more common defect. **When a fix is one line of wiring, the test belongs where the two
pieces meet** — here, `PlaybackQueueTest`, counting real extractions through a real resolver.

The habit that catches it: after writing a regression test, delete the fix and watch the test go
red. If it stays green it is testing something else, however true that something is.

## Every flow that matters has an e2e in CI (2026-08-06)

Dewi: *"make sure you have e2e of all these flows (copyright free stuff for torrents ofcourse) in
the ci/cd please"*. Not a nicety — this app is used on a phone that is not in front of anyone, so a
flow with no e2e is a flow whose next regression is found by Dewi on a plane.

| Flow | Test | Runs |
|---|---|---|
| Downloaded podcast plays offline | `OfflineQueuePlaybackTest` | every commit |
| Downloaded **video** plays offline | `OfflineQueuePlaybackTest` | every commit |
| Offline, the queue skips what it cannot play and reaches what it can | `OfflineQueuePlaybackTest` | every commit |
| Torrent: search → prepare → queue → stream from the home server | `TorrentQueuePlaybackTest` | every commit |
| Torrent: downloaded → radios off → plays from the file | `TorrentQueuePlaybackTest` | every commit |
| Preload nominated, and released once it plays | `PreloadCommandReachesServiceTest` | every commit |
| Real yt-dlp download → radios off → plays from that file | `LiveDownloadedVideoOfflineTest` | residential-egress tunnel |
| The app fetching a stream ITSELF (SABR) → radios off → plays from that file | `LiveSabrDownloadTest` | residential-egress tunnel |
| SABR playback against live YouTube | `SabrPlaybackTest` | residential-egress tunnel |
| Auto-advance, stall recovery, metered switch, silence strategy | `AutoAdvanceLoopTest`, `StalledStreamRecoveryTest`, `MeteredAudioSwitchDeviceTest`, `SilenceStrategyDeviceTest` | every commit |
| A quiet podcast is made loud with ZERO samples clipped, incl. across transients | `LoudnessBoostTest` (JVM maths), `BoostingAudioProcessorTest` (Media3 buffers) | every commit |
| Screen brightness set by swipe survives going fullscreen and coming back | `BrightnessSurvivesFullscreenTest` | every commit |
| A video with an auto-dub plays in your language, and the track menu overrides it | `AudioTrackTagTest`, `AudioLanguageSelectionTest`, `VideoQualityTest`, `AudioTrackSelectionTest`, `AudioTrackMenuTest` | every commit |
| Fullscreen survives an auto-advance, including a stream that fails and re-resolves | `VideoPresenceTest`, `FullscreenSurvivesTheNextVideoTest` | every commit |
| The swipe brightness applies in fullscreen and nowhere else | `ChosenBrightnessTest`, `BrightnessIsFullscreenOnlyTest` | every commit |
| Speed, quality and audio track are still what you chose on the next video | `SilenceRacerTest`, `StreamChoicesTest`, `ChoicesSurviveTheNextVideoTest` | every commit |
| The CLI parses what you type, picks the right stream and calls the player correctly | `CommandParsingTest`, `CliBehaviourTest`, `PlayerCommandTest`, `ProcessYtDlpEngineTest` | every commit |
| The CLI against real python, real yt-dlp and live YouTube | `LiveExtractionTest` | `RUN_LIVE_EXTRACTION=1` only |
| YouTube Music search parses, maps and reaches the screen above videos | `MusicSearchParserTest`, `InnerTubeMusicSearchSourceTest`, `SongSearchSectionTest` | every commit |
| YouTube Music against the live API | `LiveMusicSearchTest` | `RUN_LIVE_MUSIC=1` only |
| A YouTube video played from a download still reports to the account | `PlayedPillarIsTheItemsTest`, `WatchHistorySyncTest` | every commit |
| Search is attributed when signed in, and never worse when it cannot be | `AuthenticatedSearchTest` | every commit |
| The account token is attached to every client that accepts one, and no others | `AuthAttachedByIdentityTest` | every commit |
| A video whose stream keeps failing is retried afresh when you tap it again, rather than skipped on sight | `StreamRecoveryTest`, `TappingAFailedItemAgainTest` | every commit |
| A failure for an item the queue has moved on from is dropped, never replayed onto the NEW item | `StreamRecoveryTest` | every commit — seen twice in CI as `StalledStreamRecoveryTest` buffering forever at the previous item's position |
| Progress the account could not be told about is HELD and sent the moment a sender works — offline listening included | `ProgressOutboxDrainTest`, `RoomAccountProgressOutboxTest` (emulator) | every commit — the send used to be fire-and-forget and 0.1.477 shows sixteen minutes of offline listening end `fin=true -> NoSession` and vanish |
| A row shows a video watched elsewhere as watched-so-far, by the same rule resume uses | `AccountAwarePlayStateTest` | every commit — the Sutton report: half-watched on the website, blank row here |
| Resuming never waits on the account for long, and not at all offline — so the next item plays | `AccountResumePositionsTest` | every commit — report 0.1.477 "why the next video not playing??": six plays, no transition, the account read hung with no network. Proven to fail with the bound removed |
| Giving up on a failed stream does NOT move the queue on when "auto-play next" is off | `StreamRecoveryTest` | every commit — end-of-item advance and the stall watchdog honoured the setting; recovery did not |
| A row given no download callbacks still downloads through the app-wide actions | `MediaItemRowKeepsActionsTest` (emulator) | every commit — Related, Notifications and Search drew a control with `{}` behind it |
| A video whose stream will not play falls back to the copy already downloaded, rather than being skipped | `PlayRouteTest`, `StreamRecoveryTest` | every commit |
| Taps during a slow extraction start playback once, and the newest one wins | `OnlyTheNewestPlayWinsTest` | every commit |
| A resolve that lands late cannot take playback off a file it has already started — whatever route the newer play took | `AStaleResolveDoesNotClobberPlaybackTest` | every commit |
| A 403 on a URL whose lease is still good is a refusal, not an expiry, and gets one retry rather than three | `StreamLeaseVerdictTest`, `ARefusedStreamStopsRetryingTest` | every commit |
| A download that fails is tried again by itself, without waiting for the queue to change | `AFailedDownloadIsTriedAgainTest` | every commit |
| Several queued items download at once, and never more than the lane limit | `QueueAutoDownloaderTest`, `AutoDownloadFetchesTheAudioTest.severalQueuedItemsAreFetchedAtOnce` | every commit + live phase — the unit test proves the scheduler hands out lanes, the live one that the real graph acts on it (the failure if it does not is invisible: everything still downloads, just one at a time) |
| Two callers claiming the same item start ONE download | `DefaultDownloadManagerTest`, `SeveralSharedLinksAllLandTest` | every commit + live phase — needs a store that commits a moment late, as Room does; against a synchronous one the race is unreachable and the test passes against broken code |
| Several links shared into the app in quick succession all land, each fetched once | `SeveralSharedLinksAllLandTest` | live phase — asserted from the app's own `[download] start` trail, because two downloads of one item leave exactly one row behind and the damage is invisible afterwards |
| A download interrupted by the app being killed is retryable, not deleted | `DefaultDownloadManagerTest` | every commit |
| A part-fetched download is continued, and never corrupted by continuing it | `AnInterruptedDownloadResumesTest` | every commit — the range asked for, progress counted from what is on disk, a server that ignores the range, a 416, a dropped connection keeping its bytes, and the non-resumable route |
| Downloads hold the process open, as a real foreground service, and let it go afterwards | `DownloadsHoldTheProcessOpenTest` | every commit (emulator, no network) — Android's own rules about which processes keep running; a JVM test can say nothing about it |
| Automatic downloads stop the moment the network or the setting says so, mid-pass | `QueueAutoDownloaderTest` | every commit |
| "The tail is not coming" is only said when the stop actually left playback unable to continue | `LoadStopIsAFaultTest` | every commit |
| The stream held for the next item is the stream that then plays — not a second guess at it | `ThePreloadIsTheStreamThatPlaysTest` | every commit |
| Subtitle tracks reach the player and a choice sticks | `SubtitlesArriveAndRenderTest` | live phase — the plumbing was unit-tested at both ends and the middle (a real resolve handing tracks to a real ExoPlayer) by nothing |
| A degraded extraction explains itself | `DurableUrlsOnDeviceTest` | live phase — asserts a durable URL **or** yt-dlp's reason; asserting the URL alone was testing YouTube's per-session policy |
| Durability outranks codec preference but never decodability, incl. at 4K | `TheLadderPrefersDurableStreamsTest` | every commit |
| Sound plays on every content type — short, long, made-for-kids, live | `PlaysAcrossContentTypesTest` | live phase — asserts the position ADVANCES (a player stuck at 1ms reports playing) and reports the picture per type rather than demanding it, because whether a picture survives is YouTube's choice |
| Seeking an HOUR into a 97-minute video plays on from there | `SeekDeepIntoALongVideoTest` | live phase — the sharpest test of the 2026-08-18 cap: ~30MB in, 30x past the ceiling, and it asserts playback CONTINUES rather than merely arriving |
| The phone's own QuickJS produces a URL with a deciphered `n` | `DurableUrlsOnDeviceTest` | live phase — a fix measured with node on a laptop would otherwise be a no-op on the device with a green JVM suite |
| SABR delivers the window it is offered, and stops only when the server SAYS so | `SabrCarriesAWholeStreamTest` | live phase — deliberately asserts our own machinery, not YouTube's policy: a test demanding 80% of a stream would be red every run until a PO token exists, and a permanently red build is what taught everyone to wave the last skip through |
| The claimed SABR position never outruns the bytes in hand | `ClaimedTimeFollowsTheBytesTest` | every commit |
| The silence detector hears BOTH channels, and refuses to judge a format it cannot read | `SilenceDetectorHearsBothChannelsTest` | every commit — instrumented, no device behaviour. Its stride shared a factor with the stereo frame size, so it only ever read channel 0 |
| A refused stream tries SABR before giving up the picture — and the sound still saves it when SABR refuses too | `TheSabrRungKeepsThePictureTest` | every commit — incl. that a copy on disk still wins, and that hour-deep SABR is not offered at all (it cannot seek, and "succeeding" from the top would throw away your place) |
| Every format `SabrResolve` is willing to choose actually delivers bytes | `SabrServesWhatWeChooseTest` | live phase — asserts ours, PRINTS what YouTube allows beyond the 1080p/30fps caps. A test that went red because YouTube RELAXED a restriction would be failing on good news |
| A SABR stream opened part-way through asks for the matching media TIME, not the byte offset | `OpeningAtAnOffsetAsksForThatTimeTest` | every commit — and the opposite: sequential reading must NOT re-estimate, or the claim can move backwards and be read as a seek |
| Whether YouTube serves a cold mid-stream jump | `SabrServesWhatWeChooseTest.sabrCanBeOpenedPartWayThrough` | live phase — a PROBE: asserts only that the request aims at the right time, and measures what was served **on the wire** (it wraps the transport), so ~1KB responses cannot be a reader defect |
| Whether a WARM jump is served | `SabrServesWhatWeChooseTest.aJumpInsideAnEstablishedConversation` | live phase — ⚠️ **an unsound instrument for a negative result**, and it published one: it judges at the reader, asks for a mid-segment byte `read` can never key, and aims past the ~1MB ceiling. Session continuity is REOPENED; see `docs/todos/sabr-cannot-seek.md` for what a sound version needs |
| A stalled SABR read is a FAULT, not the end of the video — and does not spend the stream it stalled on | `AStuckStreamIsNotTheEndOfTheVideoTest`, `APrematureSabrEndIsNotTheEndTest` | every commit (the second on the emulator) — the second door into the premature end `APrematureEndIsAFailureTest` guards |
| A warm cached stream can rewind to byte 0 and play on | `AWarmStreamCanRewindToTheStartTest` | every commit — the fake answers from what the request actually said, BOTH the claim and the ranges, because a fake reading `player_time_ms` alone passed a broken rewind |
| Bytes we discarded never move the claimed position | `DiscardedBytesDoNotMoveTheClaimTest` | every commit — incl. the other track's itag, whose byte space this format does not use |
| A failed round trip is not an empty answer — the claim stays, and it is logged once | `ANetworkErrorIsNotAnEmptyAnswerTest`, `AFailedRoundTripCostsNoMediaTest` | every commit — the first drives the real transport against a loopback socket, because the defect is in what `HttpURLConnection` does on a 4xx |
| Which recovery wins when a SABR stall and a dead network are the same event | `SabrStallOutranksTheNetworkTest` | every commit — pins a deliberate trade (a stall gets a fresh resolve, not a wait) that had no test at all |
| Raising the quality cap actually reaches the ladder, and 4K arrives with picture and sound | `FourKActuallyPlaysTest` | live phase — asserted on BOTH paths, so a regression that stopped the setting working cannot hide behind YouTube refusing the stream |
| What the signed-in TV client's ladder contains | `WhatTheTvClientWillServeTest` | live phase, needs `TOTUM_ACCESS_TOKEN` — reports, never asserts; skips without a token because "no credentials here" is not a finding about the app |
| A few empty SABR answers spread over a long stream do not end it | `EmptyRunsDoNotEndTheStreamTest` | every commit |
| Every SABR request tells the server which segments we already hold | `BufferedRangesAreSentTest`, `TheStreamTellsTheServerWhatItHoldsTest` | every commit |
| A refused download is tried by a DIFFERENT route, not the one that refused it | `DownloadFailureTest` | every commit |
| YouTube still serves a whole stream at all (no commit needed to break this) | `tools/ci/youtube_canary_test.py` + the hourly Pi timer | hourly, off-CI |
| The subscription list is not fetched twice in a session | `SubscriptionsFetchedOnceTest` | every commit |
| A report's own numbers survive minification and reset per item | `PlayHandleLabelTest`, `AnalyticsResetPerItemTest` | every commit (the second on the emulator) |
| What you type into "Send diagnostics" reaches the report | `DiagnosticsNoteTest`, `DiagnosticsContentTest`, `DiagnosticsNoteBoxTest` | every commit |
| A repeated heartbeat is collapsed, and how long it repeated is still stated | `ActivitySnapshotterTest` | every commit |
| Channel, views and date each get their own line — in a list and on the video page — so a long channel name cannot hide them | `MediaItemSubtitleTest`, `FactsOnSeparateLinesTest` | every commit |
| Each fact wears its own emoji, no two alike, and never a bare glyph with nothing to label | `MediaItemSubtitleTest` | every commit |
| Shorts reach the feed tagged, without the videos waiting on them | `FeedWithShortsTest`, `SubscriptionShortsTest`, `ShortsReachTheFeedTest` | every commit |
| The channel line is a channel — never the view count, never a separator | `VideoTileParserTest` | every commit |
| Tapping a Short opens the reel on that Short, with the rest of the feed's Shorts around it | `ShortsReelTest` | every commit |
| A video watched on another device resumes where you left it, and the watching device keeps its exact position | `ResumeChoiceTest`, `AccountResumePositionsTest`, `VideoTileParserTest` | every commit |

**Nothing copyrighted is ever fetched.** The torrent tests use a stand-in that speaks Prowlarr's and
TorrServer's protocols, media generated by this repo (a silent WAV, or `clip.mp4` — 90s of black
H.264 made with ffmpeg), and titles naming genuinely public-domain films. No magnet is resolved, no
peer is contacted, no swarm exists.

**Two things are deliberately NOT covered, and are named rather than left looking covered:** Listen
mode's remuxed torrent audio (real HLS, which a stand-in cannot serve honestly), and anything about
the real Pi — CI's tunnel peer is firewalled to internet-only egress and the home services are behind
a Google login the app cannot complete unattended yet.

## A constant input cannot test anything that only happens during a CHANGE (2026-08-08)

The volume boost shipped with **eleven** JVM tests and a distortion bug Dewi heard within the hour.
Every one of those tests fed it a **constant tone**, so the gain had always finished settling before
anything was measured — and the defect only existed while the level was *changing*. Real speech is
quiet and then somebody laughs; not one test played that.

Three things to take from it, all cheap:

- **Test the transition, not the steady state.** Any component with memory — a gain, a buffer, a
  cache, a retry counter, a smoothing filter — behaves differently while it is catching up than once
  it has caught up, and the interesting failures live in the catching up. `quiet → sudden loud` found
  it in one line.
- **A threshold in an assertion is a claim about what is acceptable; make it what you actually
  mean.** The clipping test permitted 5% of samples pinned to the rail. The real clipping came to 3%,
  so it passed. There was never a good reason to accept *any* — the assertion should have been zero
  from the start, and once the design guaranteed zero, zero is exactly what it asserts.
- **The fix produced a second bug the same tests would have missed.** Replacing the fixed levels with
  an automatic one introduced an absolute noise floor, which made the *quietest* recordings measure
  as silence and get **no** boost — the whole point of the feature, inverted. It was caught only by a
  test that swept several input levels and asserted the relationship between them
  (`the quieter the recording, the more gain it gets` → `[1.0, 6.7, 1.9, 1.0]`). **Where behaviour
  should vary with an input, test the trend across several values**, not one value in isolation.

## Some bugs live in the ORDER, not in any function (2026-08-08)

The brightness bug — gesture-set brightness wiped every time you entered fullscreen — had no wrong
line in it. Applying the remembered level when a stage appears is right. Releasing the override when
a stage goes away is right. The defect was only that Compose runs the first during **composition**
and the second during the **effects** phase, so across a subtree swap the release always landed last.

**Every unit test passed, before and after.** There is no single function to point a JVM test at,
because no single function is wrong. That is the signature of this bug class:

- **State that lives in a shared resource** — the window, the audio session, a system service, a
  file — rather than in the component that sets it.
- **Two components with overlapping lifetimes**, where "the old one tidies up" and "the new one sets
  up" race. Any swap of a subtree does this: navigation, a fullscreen toggle, a list item recycling.

Two rules follow, both cheap:

- **Make the answer order-independent rather than getting the order right.** Counting owners and
  releasing on the last one is immune to which way round the calls arrive; a boolean or a
  "did I set it" flag on either side is not, and cannot be, because neither side knows about the
  other. The JVM test then asserts *both* orders explicitly.
- **Test it against the real composition.** `BrightnessSurvivesFullscreenTest` drives the real
  `VideoStageWithControls` through the real swap and reads the real `window.attributes`. It failed on
  the first run with `expected:<0.87> but was:<-1.0>`, before any fix existed. Nothing cheaper than a
  real composition would have caught it — and the doc closing this area had already predicted the
  test was needed, then shipped without it, which is exactly how the wrong claim survived three days.

## A test host activity is not the app's activity (2026-08-09)

Every assertion in a new instrumented test failed with *"No compose hierarchies found in the app"*,
which reads like `setContent` was never called. It had been. Entering fullscreen rotates the
screen, and the activity `createAndroidComposeRule<ComponentActivity>()` launches — from
`ui-test-manifest` — handles **no** configuration changes, so it was destroyed mid-test and took
the composition with it. The real `MainActivity` has always declared `configChanges`; the test host
inherited none of it.

Fixed with `app/src/debug/AndroidManifest.xml` overriding that activity with the same list. Note
**debug**, not **androidTest**: the host activity is declared in the app-under-test's manifest, and
an override placed in the androidTest source set merges into the wrong manifest and silently does
nothing — which cost a run to work out.

The general lesson: when a UI test fails in a way that says the content is missing, ask whether
something in the code under test *restarted the activity*. Rotation, locale changes, night-mode
switches and window-size changes all do, and all are invisible in the test's own code.

## Fixing the chooser is not the same as fixing the choice (2026-08-09)

The wrong-language bug had an obvious culprit: a stream picker that sorted by height and never
looked at the audio language. It was fixed, six new unit tests went green against it, and the
end-to-end test through the launcher **still played the German dub**.

The picker was one of five. The one that actually decided was the *quality ladder* that fed it:
it offered 1080p — which existed only as the dub — and the auto-pick takes the tallest. The unit
tests could not see that, because each was true about the function it tested.

Then the corrected rule was wrong in the other direction, and again a test found it: asking for
German kept a 720p English entry, because the bar it compared against was the best **audio-only**
track rather than the best sound available anywhere on the video.

Two rules from one afternoon:

- **Where several components answer the same question, test the ANSWER, not each component.**
  The launcher test — "does an English talk play in English?" — is the only one that could fail
  while every unit test passed. Write that one first.
- **When you find one place with a rule, go and count the others.** Five pickers answered "which
  stream?" and four of them were wrong; the one already fixed (in July, for the same bug) is what
  made it look handled. A grep for the sibling call sites is minutes, and it is the difference
  between fixing a bug and fixing an instance of it.

## A stand-in must BEHAVE, not merely claim (2026-08-06)

`TorrentQueuePlaybackTest`'s fake home server advertised `Accept-Ranges: bytes` and then ignored
every `Range` header, answering 200 with the whole file. It passed locally — the player opens at zero
and never notices — and **failed in CI**, where the slower emulator rebuffered, asked for a range,
got the whole file with a 200, and never started playing. A flake that passes here and fails there is
the worst kind, because the natural response is to re-run it.

The lesson is not "raise the timeout" (that was also needed: CI's emulator is far slower, so 60s):
it is that a stand-in claiming a capability has to implement it, or it tests the app against a server
that does not exist. It now serves real 206 responses with a `Content-Range`.

## An intermittent failure is a race, not a flake, until proven otherwise (2026-08-07)

`PlayerMetadataTest` failed on CI, passed on the next run, then failed again on a commit that
touched only test files and docs. The tempting reading is "flaky test". The real one was that the
production channel was unsound: the view count rode in `MediaMetadata.extras`, which a
`MediaController`'s copy of an item does not dependably carry, so whether the page showed the
numbers came down to timing. On a device it would have shown them and then dropped them.

Fixed by holding the values on the controller, as the skip segments and subtitles already were.
Then run five times consecutively — because "it passed once after I changed something" is how a race
gets declared fixed while still being a race.

**That fix was real and it was not the whole story** (2026-08-19). The same test failed the emulator
job twice more today, and this section — which reads as though the matter was closed — is part of why
the second reading was "flaky" again. There was a SECOND race, in the test rather than the product:
the item's URI is unreachable on purpose, and how long that takes to discover is a property of the
machine. Locally DNS and connect take long enough that the poll loop still finds the state; with no
route the load fails in **73ms**, the player goes idle, the session lands on nothing, and `state.value`
has already been torn down. Turning the emulator's network off reproduces it every time.

A transient value has to be **collected from the stream, not sampled after the fact** — buffered, so
StateFlow cannot conflate the publication away — and the wait has to be for a state that carries what
is being asserted, because the queue's path publishes a bare state for the id first. Both halves were
races the poll loop could win on one machine and lose on another. See also [a test can measure the
environment].

## The app can change the precondition you just set (2026-08-19)

`SubtitlesArriveAndRenderTest` failed CI as *"no subtitle track reached the player"*. It passes in
isolation on the emulator, so it was never YouTube and never the caption path. The trail said
`listen=true` **eight lines after the test set `PlaybackMode.VIDEO`** — and `audioPlaybackPreferred()`
returns `false` for an explicit VIDEO, so the mode genuinely was not VIDEO when the route was taken.

The first guess was `MeteredAudioSwitch`, which PERSISTS its decision (`setPlaybackMode(AUDIO)`) and
samples on a clock, so on a metered connection it can fire between a precondition and its measurement.
**That guess was wrong**, and the fix built on it did not fire in CI: the mode genuinely was VIDEO. The
trail's `listen=` field is `forceAudio || pictureGivenUpOn || audioPreferred()`, and what had actually
happened was the **recovery ladder degrading a stream YouTube refused** — the attestation wall — leaving
an audio-only route. Three different causes, one observable, and only the observable is worth testing.

An audio-only route has no picture, no captions and no quality ladder. So every test that measures one
of those can be told a lie by the app doing exactly what it is designed to do.

`PlaysAcrossContentTypesTest` met this on 2026-08-18 (three items read as "no picture") and fixed it by
restating the precondition per measurement — necessary, but not sufficient: it does not help when the
switch fires *during* the measurement. So the question "did the app route this without the picture?" now
lives in one place, `audioOnlyRouteTaken()` — asking the recorded DECISION rather than the setting, which
is what makes it right for all three causes — and the tests that measure the picture consult it and say
so rather than asserting, printing the route line as the evidence. `FourKActuallyPlaysTest` needed it for the same reason: an audio-only
route picks no video height, so its one assertion would have read "picked 0p" and blamed the cap.

The general shape, and it is not only about this switch: **a precondition that the code under test is
allowed to change is not a precondition, it is a race.** Either take the ability away for the duration,
or check it still holds at the moment you measure — and when it does not, report *that* rather than the
thing you were trying to measure. Same family as [a test can measure the environment].

## A timeout is not a diagnosis (2026-08-19)

`ShortsReelAdvanceTest > endingRepeatedly_walksTheWholeReel` failed on main (run 32239962730) on a
**docs-only** commit, and the entire report was
`ComposeTimeoutException: Condition still not satisfied after 5000 ms`.

That one line is consistent with at least four different causes — the advancer never heard the end,
it heard it and the queue refused every remaining item, it advanced onto the wrong short, or nothing
was playing when the test ended it — and it separates none of them. So the investigation had to run
on code-reading and elimination. Two plausible theories died that way: a stale `nowPlaying` racing
the advance (impossible — `_nowPlaying` is committed *before* routing) and connectivity making
`routeNow` refuse (impossible — `FakeAppContainer.isOffline()` is hardcoded false). A third, that
`waitForIdle()` can return before the reel has started because the first short is started by a
coroutine rather than by composition, is **reachable in the code but did not reproduce** in 10
probe runs under host CPU load, nor in a full 104-test suite run.

So the cause is not proven, and nothing here claims to fix it. What changed is that a recurrence
will now say what happened:

- **The wait reports.** Every wait in that test goes through `waitForPlaying`, which on timeout
  raises what is playing, what is queued, where the cursor is, and the `advance`/`queue`/`playback`
  breadcrumb trail. The trail was always being recorded — it just was not in the failure.
- **`FakePlaybackController.endCurrent()` no longer no-ops.** It built its event from `state.value`
  behind a `?.let`, so ending before anything played emitted nothing and left no trace: the advancer
  heard nothing, and 5s later the symptom read as "the reel does not advance", the opposite of the
  truth. It now fails immediately and names itself (`EndingNothingIsATestBugTest`), and `openReel()`
  waits for playback to have started rather than trusting `waitForIdle()`.

The rule: when a test can fail for several reasons, its failure has to say which one. An assertion
that reports only *that* a condition was unmet is the test equivalent of an unlogged branch. And a
`ComposeTimeoutException` is a fact about the wait, not about the product — the same trap as reading
an empty capture as an empty world.

## When an environment disagrees about a NUMBER, believe it (2026-08-07)

CI reported exactly 1 move for a drag test where the local emulator reported 10. It was written off
as frame timing and the test was moved to the JVM. Dewi then reported from his phone: *"i am only
able to drag the items in the queue by 1 position"* — the same number. CI had found a real bug on
the one configuration whose timing let a frame through mid-gesture, and it was overruled.

A flake is a test that gives *different* answers to the same question. A test that reliably gives a
different answer in another environment is describing that environment, and one of them is your
users'. The cost here was shipping a broken dragger and writing a doc claiming it worked.

## A passing e2e is not the same as a reproduction (2026-08-06)

Reverting the fix and watching the test fail is the only thing that tells you what a test covers, and
on this fix it overturned the diagnosis.

`StreamPlaysToItsEndTest` (generated WAV over localhost) and `LiveStreamPlaysToItsEndTest` (a real
YouTube stream) both play an item resumed seconds from its end through the real queue, session,
service and player — the whole flow behind Dewi's *"buffers towards the end of the video"*. **Both
pass with the fixed defects deliberately reinstated.** The WAV result had an explanation that
predicted the live one would fail; it did not, so the explanation was wrong as well.

What *does* fail without the fix, at two levels each: `ChunkedReadTest` (6 of 18 cases) and
`ChunkedDataSourceTest` (2 of 6 on the arithmetic, plus the read-cap assertion tripping on the
infinite loop). Those are the tests that prove something.

The lesson is not "write more e2e". It is that **an e2e passing either side of a change tells you
nothing about the change**, and reporting it as coverage would have shipped a wrong root cause with a
green tick beside it. Full write-up: `../todos/stalls-near-the-end-of-an-item.md`.

Full write-up: `../todos/stalls-near-the-end-of-an-item.md`.

## A test whose name is broader than its coverage hides the gap (2026-08-06)

`OfflineSkipsUnavailableTest` had *"offline, a video is declined without resolving"* — true, and
the reason the missing case was never noticed: read as a rule, it says videos cannot play offline,
which is what the code did and precisely the bug. There was **no** test for a video that HAD been
downloaded, and the whole offline feature is about downloaded things.

So: **name a test for the case it actually covers**, and when a rule has an obvious other half,
write both halves or neither. The renamed one now says *"a video **with no copy**"*, and the four
tests beside it cover the copy. Nine tests across two tiers go red if the fix is reverted.

Where the tiers landed for that fix, as a worked example of the pyramid:

| Tier | Count | What only this tier can prove |
|---|---|---|
| Unit (`:core:domain` `PlayRouteTest`) | 17 | every combination of pillar × copy variant × offline × Listen, instantly |
| Integration (`app` `OfflineSkipsUnavailableTest`) | 11 | the queue really consults the store and really advances past what it cannot play |
| Instrumented (`OfflineQueuePlaybackTest`) | 3 | radios genuinely off, real Room, real Media3, on a device |
| Instrumented + live (`LiveDownloadedVideoOfflineTest`) | 1 | yt-dlp fetching a real YouTube video, then playing that file offline |

The live one runs only through the residential-egress tunnel, which is allowed to skip — so it
adds proof but can never be the only proof. Both, not either.

## A measurement in a `const` is a fact with no expiry date (2026-08-18)

`SabrResolve` refuses to pick any format above 1080p or 30fps, and both numbers are measurements of
YouTube's behaviour taken on 2026-07-31 and then frozen into a constant. Nothing re-checked them, and
they can rot in **two opposite directions**:

* **Stricter** — a format we still pick stops being served, so playback breaks while our picker
  behaves exactly as designed. No unit test can see this; the code is right and the world moved.
* **Looser** — 60fps or 2160p start being served and we refuse them forever, so a 4K60 upload plays
  at 1080p30 with nothing anywhere saying why. This is the shape of the "works great in SmartTube" gap.

`SabrServesWhatWeChooseTest` (live, JVM) closes both. It **asserts only our half** — every format our
own picker chooses must actually deliver bytes — and *prints* what YouTube allows beyond the caps
without asserting it. A test that went red because YouTube RELAXED a restriction would be failing on
good news, which is the same mistake as asserting someone else's policy; this repo made that one three
times in a day, so the pattern is now explicit: **assert ours, report theirs.**

Its printed line is as much the deliverable as the green tick, because it is the only place that says
what quality YouTube would serve today versus what we ask for. Run 2026-08-18, live:

```
[sabr] our own picks: audio 474KB, video 115KB
[sabr] confirmed still refused: itag 401 2160p60 served 0KB. Our caps remain correct.
```

## Two ways a live test measured the environment instead of the app (2026-08-18)

`PlaysAcrossContentTypesTest` reported `sound=YES picture=no` for two fixtures and a bare
`UNEXPLAINED: sound=NO` for the live stream. Neither was true of the app. Both were the test.

**1. Forward motion is not playback.** Liveness was `positionMs > from + 1500`. A live stream's
position is an offset into a window that *slides*, so when the manifest expired and re-resolved the
position went from 14120ms to **11523ms** — backwards — and the test called a stream it could see
playing "silent". The measure is absolute movement, not increase. Any assertion of the form
`value > previous` on a quantity that can legitimately fall is measuring VOD-ness, not liveness.

**2. "No picture" has three causes and only one is ours.** The emulator's Wi-Fi reported METERED, so
`MeteredAudioSwitch` — working exactly as designed — dropped the picture about five seconds into every
item, racing the picture measurement and making the column move run to run while nothing changed.
Separately, YouTube refused the 19-second clip's video stream (403 from `ANDROID_VR` with 21577s of
lease left) and the rescue ladder kept the sound, which is also correct. So the report now names the
cause instead of printing a boolean:

| Reported as | What happened | Whose fault |
|---|---|---|
| `downgraded` | mobile data held, the app dropped the picture on purpose | nobody — a feature |
| `rescued` | YouTube refused the video stream, the app kept the sound | YouTube, handled |
| `no` | absent and nothing explains it | **ours** |

A data-saving feature working perfectly must never read as a missing picture; reading it that way
would have sent the next session hunting a video bug that does not exist. Unmeter an emulator with
`adb shell cmd netpolicy set metered-network '"AndroidWifi"' false`.

The general lesson, and the reason this sits next to the assert-ours-report-theirs rule: **a test
whose result is dominated by an environment condition it does not control or state is not evidence.**
It was reported as "sound and picture verified across four content types", and one of those columns
was really measuring the emulator's metered flag.

## Reading the results without downloading anything (2026-08-11)

Dewi asked whether GitHub has a GUI for test pass/fail history. It does not: Actions shows ✅/❌
per **job**, never per test, and does not parse JUnit XML at all. Ours was worse than it needed
to be as well — unit-test HTML was uploaded only **on failure**, so a passing run left no way to
ask "how long does this take now" or "did that flaky one skip again".

Now every run's page carries a table: totals, and each failure named with its message, for unit
tests and for the emulator job. Failures also become run annotations. `tools/ci/test-summary.py`
reads the XML the test tasks already write — no third-party action, no extra token permissions,
and nothing that can break because somebody re-tagged a release.

Two details worth keeping:

- **"No results" is reported explicitly**, and is the reason the script exists in this shape. A
  test task that silently stopped running renders identically to a clean pass otherwise, which is
  the worst possible green.
- **It can never fail the build.** It exits 0 always; the test task is what decides. A summariser
  that turns a green build red is a summariser people delete.

It has its own tests (`tools/ci/test_summary_test.py`, run as part of the same CI step), and they
earned it immediately: run as a step with the real `GITHUB_STEP_SUMMARY` set, they appended two
bogus "No test results found" blocks to the actual job summary — the tests writing into the thing
they were testing.

**Still missing: history.** This is per-run. Trends across runs — pass rate, durations, which test
fails intermittently — need the XML kept somewhere. The cheapest durable option is the Pi, which
already runs the crashlog server with a SQLite index behind Google auth; that pattern would make
"is `SearchSectionStatesTest` flaky?" answerable, which it currently is not.
