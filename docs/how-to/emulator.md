---
title: The emulator totum-api35 and its perishable YouTube sign-in
kind: reference
updated: 2026-11-28
---

# The emulator

The rule itself is in `CLAUDE.md` (hard stops 1 and 2, and "The emulator"), which every session loads and which wins if the two ever disagree; change the rule there and here in the same commit. This file is the full account: the procedure, the numbers and the incidents that earned it, moved from `CLAUDE.md` at commit `698933d9` on 2026-11-28.

### The emulator: `totum-api35`, and its YouTube sign-in is perishable

The project's emulator AVD is **`totum-api35`** (API 35, x86_64). Boot it with
`-gpu swiftshader_indirect` — the hardware GPU segfaults on sustained 4K/live decode and takes the whole
emulator down mid-test.

**One emulator, owned by the orchestrator** (2026-11-13: two builders each booted `totum-api35` at once, both
copies died, and RAM allows only one). The orchestrator boots it; a builder never does. A builder takes
`flock /tmp/totum-emulator.lock` for each short session and checks `adb -s emulator-5554 emu avd name` says
`totum-api35` (another session's SmartTube emulator has used 5554). Install the app and test APKs AND run the tests inside one lock hold: another
builder's install between them swaps the app under the test and fails it falsely (seen 2026-11-13). Boot with the isolated **36.6.11** binary,
because the SDK's 37.2 hangs and crashes on a cold start here: `systemd-run --user --scope
/home/dewi/claude-tasks/totum-playback-fixes-20261012/emulator-36/emulator/emulator -avd totum-api35 -no-window -gpu
swiftshader_indirect -no-boot-anim -no-audio -no-snapshot -cores 2 -memory 2048` (its own scope, so an OOM kill takes
the emulator, not the session).

To reproduce a report with the phone's own queue and settings, `tools/emulator/load-state.sh [dump]` loads the newest state dump from the Pi (read-only ssh) into `totum-api35` through the debug-only `StateDebugReceiver`, never touching the sign-in ([ADR 159](../adr/0159-a-crash-screen-and-a-state-dump-for-every-report.md)); it finds the AVD by name because the serial is not always 5554.

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
  `adb shell am instrument -w -r -e class <FQN> com.dewijones92.totum.test/com.dewijones92.totum.TotumTestRunner`
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
