---
title: Testing
kind: reference
updated: 2026-08-13
---

# Testing

Testing pyramid: many fast JVM unit tests, fewer integration, few
instrumented/UI. **New behaviour lands with tests.** ~50 unit-test files, ~5
instrumented.

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
| yt-dlp `BridgeJson` | JVM unit | `:lib:ytdlp-chaquopy` |
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
| The line under every video title (`author · views · date`) | JVM unit | `:app` `MediaItemSubtitleTest` — testable at all only because `@Composable` came off the formatter |
| What a resolution may change about an item | JVM unit | `:core:domain` `WithStreamFromTest` — the rule that stops views/dates being destroyed at play time |
| Views + dates on a **page-2** feed video | JVM unit | `:app` `VideosPagingTest` — where "scrolled down" can actually break |
| Views + dates crossing the media session | instrumented | `:app` `PlayerMetadataTest` — extras written but never read compile fine and deliver nothing |
| Views + dates on a row 60 deep, the last row, and one scrolled back into view | instrumented | `:app` `ScrolledRowMetadataTest` |
| A queue drag continuing the same item across swaps, reversals, and the list resizing | JVM unit | `:app` `ReorderStateTest` |
| A queue drag surviving the list changing SIZE under it | instrumented | `:app` `ReorderAutoScrollTest` — `itemCount` was the gesture's second key; watched failing at 0 moves |
| A queue drag surviving its own swaps | instrumented | `:app` `ReorderAutoScrollTest` — needs composition to settle BETWEEN pointer events; a frozen clock (every other case there) cannot see it, which is why a one-place-only drag shipped |
| A queue drag of ten places in one motion | JVM unit | `:app` `ReorderStateTest` — the accumulator over distance. Deliberately NOT a gesture: that version failed on CI twice on screen size and frame timing while passing locally |
| Views + dates surviving the **database** | instrumented | `:core:database` `ItemFactsSurviveStorageTest` — queue and history; the boundary where they were dying |

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
