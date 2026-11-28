# Totum

> **Two apps live in this repo.** **Totum** (the Android app) is everything at the root. **Aerarium**
> (Dewi's read-only finance hub, a TypeScript web app on the Pi) is everything under `aerarium/`, with its own
> `aerarium/CLAUDE.md`, `aerarium/AGENTS.md`, `aerarium/docs/` (ADRs, features, todos, tests), CI workflow
> and releases. The general rules in this file (DRY and one seam, the testing pyramid, compile-time safety,
> generous `dewidebug` logging, visible async work, wrapping text, emoji-led state strings, living docs, ADR
> CRUD) bind **both** apps; the Android, YouTube, Gradle, emulator and APK-release sections are Totum-only.
> Working in `aerarium/`, read `aerarium/CLAUDE.md` as well, and record Aerarium decisions in
> `aerarium/docs/adr/`, never the root `docs/adr/`. Neither app's CI builds, releases or blocks the other
> ([Aerarium ADR 0001](aerarium/docs/adr/0001-a-second-app-in-the-totum-repo.md)).

**Read this whole file before you work: about 410 lines, never `head` it.** It holds every rule. The detail that used to
sit here (the Decisions table, recipes, incident histories) is in `docs/`, and "Read before you…" below says which doc is
required reading for which job.

**Totum** — Latin for "the whole" (was UniApp until 2026-07-25; `applicationId` `com.dewijones92.totum`). One Android app
that replaces **PipePipe** (YouTube-style streaming) and **AntennaPod** (podcasts): streaming and podcasts are both
first-class pillars on one unified domain model, so subscriptions, queue, downloads and history behave identically
whether the source is a video channel or an RSS feed. The original brief is `init`; the decisions here supersede it.

## Hard stops

Breaking one of these destroys something only Dewi can restore, costs a release, or is not yours to decide. 1 to 4 and 6
are Totum's; 5 and 7 bind both apps.

1. **Never run instrumented tests via `./gradlew` on the signed-in emulator.** `totum-api35` is signed in to Dewi's real
   YouTube account, and a fresh sign-in needs a device code only he can approve at google.com/device.
   `./gradlew connectedAndroidTest` UNINSTALLS the app when it finishes, which destroys the sign-in; so does `pm clear` and
   any reinstall that wipes data. Use `adb shell am instrument -w -r -e class <FQN>
   com.dewijones92.totum.test/com.dewijones92.totum.TotumTestRunner` against an already-installed APK, and `adb install -r`
   (never `-t` fresh) when the APK must change. **A test must never touch the real token store** (`SharedPrefsTokenStore`
   takes a `prefsName` for exactly that reason).
2. **One emulator, owned by the orchestrator** (2026-11-13: two builders each booted `totum-api35`, both copies died, and
   RAM allows only one). A builder never boots it; it takes `flock /tmp/totum-emulator.lock` for each short session, checks
   `adb -s emulator-5554 emu avd name` says `totum-api35`, and installs the app and test APKs AND runs the tests inside one
   lock hold (another builder's install between them fails the test falsely).
3. **Never re-run an older `main` CI run while one is in flight**: `ci.yml` sets `concurrency: cancel-in-progress`, so it
   *cancels the current one*, and the tip run is what publishes the APK; a spuriously failed old run is proved by the newer
   commit, the tip being a superset. The release is published when the build job ("Quality gates + build", ~17-19 min)
   ends, so **pushes closer together than that release nothing**: leave ~20 min between pushes, or batch commits into one
   push. Check `gh release view <tag> --json targetCommitish` before concluding a fix did not ship.
4. **Every YouTube call goes through the one gate** (at most 4 in flight, back-off on a 429 or a "not a bot" refusal),
   on the phone, the Pi and the CLI: "Be a polite client to YouTube" below.
5. **Nothing secret or personal goes into the repo (it is public), a log or a link**: the release signing key ("Repo,
   signing, releases" below), the emulator's token file `~/.credentials/totum-emulator/youtube_account.xml`, Dewi's state
   dumps in `~/claude-tasks/state-dumps/` (600, never committed or sent anywhere), home-server tokens (header only; ADR 90).
6. **Torrent tests use copyright-free media only** — media this repo generates, and public-domain titles — and never
   resolve a real magnet.
7. **Stop and ask Dewi before** building a pillar-specific split, building a deliberate inconsistency, adding a second
   copy of logic that already exists, changing the behaviour of something shipped or settling a trade-off with no clear
   default, or writing any of a batch's code. Each is spelled out below.

## Quality bar (non-negotiable; binds both apps)

- **Unified, always** — this and DRY are the project's twin laws. The app has two pillars but every capability gets ONE
  seam that serves both: one domain model (`MediaItem` is a podcast episode *and* a video), one playback path
  (`PlaybackController` + one mini player), one search port (`SearchSource` → sealed `SearchHit`), one HTTP text port, one
  URL type. Before building any feature, ask "what is the pillar-agnostic seam?" and build that; pillar specifics live in
  small adapters behind it. A feature implemented twice — once per pillar — is a design failure even if neither copy
  shares a line. **Every feature must be unified across YouTube/video and podcasts by default.** A pillar-specific split
  is allowed *only* when there is a genuinely strong technical reason it cannot be one seam — and when that happens you
  must stop and surface the reason to Dewi for a decision, not silently build two paths. (Dewi, explicit, 2026-07-24.)
- **Testing pyramid, every time**: many fast unit tests; fewer integration tests; few instrumented/UI tests. New behaviour
  lands with tests, and a bug fix lands with a test **written first and seen to fail against the old code** — otherwise it
  is a description of the fix rather than a guard on it. Dewi has asked for this repeatedly; it is not optional. Where
  several components answer one question, test the ANSWER end to end as well as each part.
- **Consistent everywhere, special cases discussed** (Dewi, 2026-10-31: *"I want this app to be as consistent as
  possible … in the special cases, we'll discuss"*). The same kind of thing looks, behaves and carries the same facts
  wherever it appears: an item shows the same facts in every list, an action offered for one kind of item is offered for
  every kind it makes sense for, and every path that builds an item fills what is known. When a change touches one path,
  check its siblings (every list, every way in: tap, link, notification, search, restore). A deliberate exception is never
  built silently: stop and ask Dewi, then record it in ADR 108, which also lists what the 2026-10-31 audit left to do.
  Shifted left (Dewi, 2026-11-06: *"in general for this whole repo - i want consistency"*): every builder or change checks
  the siblings of what it touches before it is done, and a consistency sweep runs after every phase of a batch, not only
  at its end, with what holds up fixed before the next phase starts.
- **No duplication from a discussed idea** (Dewi, 2026-11-01: *"if we discuss an idea, I don't want you to then
  duplicate stuff"*). Before building anything, a scout searches for code that already does each part, and the plan names
  what it **reuses** for each part; a new copy of existing logic needs a stated reason Dewi has agreed. Code that one module
  needs but another owns is **moved** to the shared module (as the queue operations moved to `:core:data` for the speaker
  queues), never re-written. The end-of-batch review checks for duplicated logic, and `./gradlew cpdCheck` (PMD CPD,
  Kotlin, 100+ tokens) fails on any duplicated block not listed in `config/cpd/baseline.txt` (remove a line once fixed).
- **Strictly DRY** — this matters a lot to Dewi. Knowledge lives in exactly one place: versions and SDK levels only in
  `gradle/libs.versions.toml`; Android build defaults (compileSdk/minSdk/Java level/lint policy) only in the root build's
  `androidDefaults`; shared code in a shared module (`:lib:common`), never copy-pasted. Before writing similar code twice,
  factor it — and if a duplication is ever deliberate, it must be recorded here with its reason. **This applies across
  front ends too**: the CLI and the Android app share one extraction stack, one bridge contract and one set of selection
  rules, and a second copy of any of them is a defect. When you find one place with a rule, go and count the others
  (five separate pickers once answered "which stream?" and four were wrong).
- **SOLID**: small focused types, dependencies point inward.
- **Maximum compile-time safety** (the brief's "dependent types" translated to Kotlin): sealed hierarchies, value classes
  over primitives, exhaustive `when`, no platform types leaking, illegal states unrepresentable.
- CI must stay green; quality gates (lint, static analysis, tests) block merges.

## Read before you…

Read the doc **in full** before the job: this file gives the rule, the doc gives the procedure, the numbers and the traps
that earned it. A builder brief names the rows that apply to its task.

| Before you… | Read |
|---|---|
| change any area | its ADRs (`docs/adr/_index.md`), its `docs/features/<name>.md`, and its rows in `docs/decisions.md` (search that file by name or ADR number; do not read it whole) |
| add a module, a seam or a dependency between modules | `docs/architecture.md` |
| boot or use the emulator, run instrumented tests, sign it in | `docs/how-to/emulator.md` |
| run a soak | `docs/how-to/soak.md` |
| bump the Room schema or edit a migration | `docs/how-to/room-schema.md` |
| refine, run or close a batch | `docs/how-to/batch-process.md` |
| add a log line, a state block or a diagnostics field | `docs/how-to/logging.md` |
| push, release, look into a cancelled CI run, touch signing | `docs/how-to/release-and-performance.md` |
| write or move a test | `docs/tests/_index.md` |
| decide what to work on | `docs/spec.md`, `docs/todos/_index.md` |
| touch `server/` or anything on the Pi | `server/README.md` (ADR 85); the crash sink is `tools/crashlog-server/` |
| work in `aerarium/` | `aerarium/CLAUDE.md` |

`AGENTS.md` is the entry point for the docs and `docs/README.md` is their map.

**Living docs:** keeping them current is part of "done": ship/change a feature → update `docs/features/<name>.md`;
start/finish/drop a backlog item → update `docs/todos/`; change coverage → update `docs/tests/`. Bump each doc's `updated`.

**CRUD the ADRs** (Dewi, 2026-10-01: *"remember to CRUD any ADR"*). Decisions live in `docs/adr/` (`NNNN-title.md`;
status, date, context, decision, consequences). Making a decision creates its ADR; changing one updates it; reversing one
marks it `superseded by NNNN` and writes the new one, all **in the same change as the code**. A row in
`docs/decisions.md` whose decision has an ADR links to it, and a new decision gets both. Read the relevant ADRs before
changing an area: they say why it is the way it is.

## Working agreements

General (both apps):

- **Opus orchestrates; Sonnet builds; Haiku investigates** (Dewi, 2026-11-13: *"I don't want Opus to build
  anything … Opus to be the orchestrator … Sonnet agents to build everything and then possibly Haikus to
  investigate"*). The main session (Opus) refines, plans, briefs, merges, deploys, tests on the real devices and
  reports; every code, test and doc change for a feature or a fix is made by a Sonnet `builder` agent in a worktree,
  and every broad read (scouts, sweeps, reuse searches) by a Haiku `scout`. The end-of-batch `reviewer` stays Opus.
  The orchestrator's own edits are limited to merge fixes (a renumbered ADR, a conflict, a row in `docs/decisions.md`),
  the task file and this file. Every builder brief names CLAUDE.md's full gate, not an `:app`-only shortcut: `detekt
  cpdCheck :cli:installDist lint compileDebugAndroidTestKotlin testDebugUnitTest test koverVerify` (unprefixed tasks run
  in every module, as CI does).
- **Iterate quickly and keep shifting left** (Dewi, 2026-10-21: *"we want to iterate quickly … any big cycles that
  take time … find bottlenecks and shift left"*). Treat a slow loop as a defect: measure where the minutes go, then move
  the check earlier and cheaper (a laptop prototype for an unknown, a debug broadcast instead of screen taps
  (`DEBUG_CAST`, `DEBUG_QUEUE_PLAY_ALL`; `tools/cast-bench/bench.sh`), a JVM test instead of a device run, the full gate
  left to CI), and fix the tooling in the same session rather than paying the cost again.
- Commit as you go — small, coherent commits at each green state.
- **Say which repo files would make life easier** (Dewi, 2026-11-20: *"if there's any file in this repo … that we need to
  CRUD to make our lives easier … improve diagnostics or something, anything … let me know. This is a rule from now
  onwards"*). Whenever work shows that creating, updating or deleting a file here would help (a missing log or state
  block, a stale doc, a script that would save a slow loop, a dead file, a fixture, a preflight check), list it to Dewi in
  the report under 🔍 Found, with the file and what it would buy, rather than letting it pass.
- **How a batch runs** (Dewi, 2026-10-14, 2026-10-15, 2026-10-23 and 2026-11-06; the orchestrator reads the full account,
  `docs/how-to/batch-process.md`, before a batch):
  1. **Refine the whole batch before building any of it** (Dewi, 2026-11-06: *"before we start a big batch of work, we
     refine a big batch of work … we investigate as we go, we probe … we shift left … scouts … a holistic approach"*); no
     code is written until Dewi says so. Refining: read every unconsidered diagnostics report
     (`tools/crashlog-server/triage.sh considered` with no date) and turn each note into a finding or an item; settle
     hypotheses with evidence, not guesses; a Haiku scout per item for what it can reuse (this repo AND the Pi, including
     `dot-files`), then one review of the whole batch for shared seams and copied code (check a scout's claim before you
     repeat it); ask Dewi only what only he can answer, a few at a time with a recommendation each, never facts the code
     can answer; write it down (`docs/todos/<item>.md` each, an overview doc, the session's task file).
     **Dewi's real app state is a lens on everything** (Dewi, 2026-11-14: *"I want my app state to be one of the lens that we
     do everything as this is real world usage"*): refining reads the newest state dump (`~/claude-tasks/state-dumps/`, 600,
     never committed or sent anywhere), every builder brief names it and asks how the change behaves at that scale and
     with those contents, and every batch ends with a pass on the emulator loaded with it (`tools/emulator/load-state.sh`)
     after the clean soak, looking at the changed screens and timing what scales with it. **Take a fresh dump before every
     big batch** (Dewi, 2026-11-20): tap the Quick Settings tile **📦 Send my state**, then run `tools/state/pull-state.sh`.
  2. **Each feature ships on its own** so Dewi can try it on his phone: tests (a bug fix's test seen red first), docs and
     ADRs in the same change, preflight and `./gradlew detekt` across all modules until clean, the changed tests run, a
     look at the changed screen on the emulator, then push and watch CI on the commit to its end. No soak per feature.
  3. **The batch ends with a soak, an Opus review and Haiku sweeps in parallel** (Dewi, 2026-11-01): one or two sweeps
     check each change for consistency across every list, way in and kind of item (tap, link, notification, search,
     restore, queue, home), and one looks for use cases the batch missed; a sweep's finding is a hypothesis, so check it
     against the code first. Then the soak (below) against the release from before the batch, and a `reviewer` agent on
     the batch's commits, given the requests and the accepted decisions. Read the whole report, check each finding against
     the code, fix what holds up with a test seen red first, and push.
  4. **Then report** in the end-of-run shape (🚧 Needs you, Changed, 🔍 Found, ⚠️ Couldn't confirm), and mark the
     phone reports the batch answered as fixed, with the first release that contains each fix.
- **Anything that asks an AI gets dry runs before it ships** (Dewi, 2026-11-01: *"anything I ask you to do with AI,
  you need to do a few dry runs, test runs … to make sure the AI is going to do the right thing and amend the prompt"*).
  Run the real model on a spread of realistic inputs (several rounds, follow-ups included), read every answer, and fix
  the prompt or the code until they come out right; a rule the model keeps ignoring is enforced in code after the call
  (as `AskAnswer.tidied` does). Record the runs and what changed in the ADR. Check the dry-run rig answers with the
  build under test (an old `totum serve` still holding the port once answered three rounds of "edited" prompts).
- **Every async operation shows itself** (Dewi, 2026-10-23: *"as a general rule of thumb in this repo … all async ops
  … should have ui indicators … notification or something"*). Anything the user starts that does not finish at once
  (a shuffle that walks pages, a page loading, a torrent preparing, a batch of downloads, a sign-in, a backup) reports
  through ONE seam, the live progress strip under every tab's header: what it is, a running count, Cancel, and a
  result when it ends (✅/❌, never silent). Work that outlives the screen also gets a notification. A new async
  feature that shows nothing while it runs is not done, and a second, private spinner is a DRY defect.
- **Text wraps; nothing truncates.** No `TextOverflow.Ellipsis` anywhere in `app/src/main`, and a `maxLines` cap only
  with a declared reason in `tools/ci/preflight.py` (Dewi, 2026-09-21: *"dont use ... please in the app - just wrap the
  text"*). A row grows to fit its title. Preflight fails on a reintroduction (a Compose test cannot see it: a truncated
  `Text` still reports its whole string).
- **Every state string starts with an emoji** (ADR 43): a status, setting value, empty or error message or notification
  carries it in the string itself; nothing spoken or used as a description carries one; one vocabulary (✅ ❌ ⚠️ ⏳ 📭 …).
- **Log generously — err on the side of far more** (Dewi, 2026-07-28: *"would we benefit from FAR MORE THINGS being
  logged??? in this repo, always err on the side of MORE LOGS/DIAGS."*). This app is debugged almost entirely from
  diagnostics reports sent off a phone that is not in front of you, so an unlogged decision is an unanswerable question.
  Every branch that decides something a user would notice (advance or don't, skip or don't, retry or give up, this
  stream or that one) **logs the decision and the reason for it**, not just the outcome; prefer a line that says why
  nothing happened. The report buffer is bounded, so anything firing many times a second is counted and logged
  periodically (`SILENCE_LOG_EVERY`), never dropped: counted, never silent.
- **Every change must be provable in the wild from its logs and diagnostics alone — this is a MUST, not a preference**
  (Dewi, 2026-08-06: *"we need to collect logs+diags verbosely to make sure what we do works in the wild … this is a
  MUST!!! and be able to be read very well after the fact"*). A feature is not done when it passes tests; it is done when
  a report sent from his phone a week later can settle **whether it actually worked there**: ask *if this silently
  misbehaves on his device, which line in the next report tells me?* If the answer is "none", the instrumentation is part
  of the change, not a follow-up. Three rules follow: **log the inputs, not only the outcome**
  (`route <id> -> … [handle=Video copy=audio-only offline=true listen=false]`); **a report must be able to answer the
  obvious next question** (state blocks carry the counters AND the per-item detail, e.g. `downloads.queueStates`);
  **write it to be read months later by someone with no context** (name the field `playing=`, `copy=`, spell the units,
  prefer a whole phrase over a flag, never let two different situations produce the same line).
- Debug logging must be prefixed `dewidebug`. **Keep it committed** until Dewi says otherwise (his standing rule, which
  reverses the earlier strip-before-commit one): these lines are often useful again. Make a chatty one reasonable — log
  only the interesting case, or lower its frequency — rather than deleting it.
- **A doc's `status` is a claim, and a stale claim is worse than none.** When you touch an area, **re-read its status
  against the code and correct it**, treat a disagreement between a file and the index as a bug in both, and when you
  find a status was wrong, say so in the doc rather than quietly fixing it — the next person needs to know the map has
  been unreliable there.
- **Every flow that matters gets an e2e in CI.** Dewi, 2026-08-06: *"make sure you have e2e of all these flows … in the
  ci/cd please"*. A flow with no e2e is one whose next regression is found on a plane. Where a flow depends on something
  CI cannot reach (the Pi, live YouTube), write **both**: a deterministic test against a stand-in that runs on every
  commit, and a live one behind `tools/ci/live-test-via-home.sh` that is allowed to skip. Never let the skippable one be
  the only coverage. Torrent tests: hard stop 6. The coverage map is `docs/tests/_index.md`, including what is
  deliberately uncovered.
- **Own the repo.** Dewi's explicit steer (2026-07-25): *"feel empowered to make big moves e.g. refactoring — you own
  this repo"*. Take the structurally right option rather than the timid one: collapse duplicated types, rename for
  honesty, move code to where it belongs. The guardrails stay — gate green, verify on-device, one coherent commit per
  move, and surface a decision that's genuinely Dewi's (a behaviour change to something shipped, or a trade-off with no
  clear default).

Totum only:

- **Be a polite client to YouTube** (Dewi, 2026-10-15: *"I want to be mindful of raising suspicion"*). Every YouTube call
  (InnerTube, yt-dlp extraction and solving, page walks, feeds, subtitles) goes through ONE global gate of at most **4 in
  flight**; nothing fans out past it, however many items a "shuffle all" or "play all" touches. Player bytes are outside
  it, and downloads keep their own cap of 4. A **429** (or `Retry-After`) means exponential back-off with jitter, never a
  tight retry; a **"not a bot"** refusal backs yt-dlp off (1, 2, 4 … 60 min, reset by a success). Either one shows a banner
  on every tab while it lasts, and is logged and counted. A new feature that talks to YouTube uses the gate rather than its
  own `async` fan-out. **The Pi's `totum serve` and the CLI are bound by it too** (2026-11-07): one `Politeness` per
  process (`Politeness.gated(engine)`, `Politeness.gated(clientBuilder)`); `HomeDownloads` and `YtDlpLiveHls` require it, so
  a new Pi path to YouTube takes it as a parameter rather than building its own client or engine.
- **Repo, signing, releases.** Remote `github.com/dewijones92/totum` (public); the laptop's clone and the Pi's are both
  `~/code/totum`; default branch `main`, pushing to it is fine and CI must stay green (CI runs: hard stop 3). Every push to
  main publishes a signed APK to its **own** release, tagged `v0.1.<run number>` to match `versionName`, deliberately
  **not** a prerelease (Dewi's Obtainium reads the repo's "Latest release"); versionCode is `100 + run number`. The release
  signing key lives in three places, never this repo: `/home/dewi/code/dewiuniapp-signing/`, this repo's Actions secrets
  (CI signing; write-only), and the PRIVATE `dewijones92/uniapp-signing-backup` (survives laptop loss). Those keep their
  old names on purpose: renaming anything holding release keys buys tidiness at the cost of risk.

## Decisions, and the seams new work must reuse

Every standing decision is a row in `docs/decisions.md`: the choice and why, most with an ADR, which is the authority.
The file is about 70 KB, so search it (`grep -n -i 'gapless' docs/decisions.md`); do not read it whole.

**A new decision** gets its ADR and its row at the end of that table, in the same change as the code. It adds a line to
*this* file only when every later change has to obey it: a seam for the list below, a foundation changed, a hard stop, or
a standing rule from Dewi (with his words, under Quality bar or Working agreements). A feature decision adds nothing here.

**Foundations**, which supersede `init`: both pillars from day one; Kotlin + Jetpack Compose, one Gradle project;
**minSdk 33** (ADR 39; Android-14 calls guarded in `reminders/kit/ForegroundKind.kt`); from-scratch `:lib:ytdlp` (no
youtubedl-android fork); Chaquopy 17; ffmpeg bundled; our own InnerTube client (`:lib:innertube`) with **TV device-code
OAuth**, yt-dlp staying for extraction and playback; GitHub Actions, signed APKs on GitHub Releases, no Play Store;
GPL-3.0 (ADR 146); a UI that is genuinely nice and modern (Material 3 expressive, dark/light, edge-to-edge, considered
motion, never template-default); brand tangerine, cyan and lemon (palette only in `theme/Color.kt`), **dynamic colour OFF
by default**.

**One seam each.** These decisions made one place for a thing; new work goes through it, never beside it. Read the row
and its ADR before you touch one.

- **A title shown or sent anywhere** (rows, player session, notifications, toasts, share, stats, Ask): `ItemShield` /
  `ItemFace` (spoiler shield, ADR 141); logs and stats records keep the real title.
- **Anything leaving the phone** (a report, a state dump, an Ask snapshot): the one shared redactor (ADRs 140, 159);
  reports reach the Pi only from a phone signed in to it (ADR 146).
- **Home-server calls**: `HomeServerAuth`, a per-device token in the `X-Totum-Token` header only, never in a link (ADR 90;
  the one exception is the cast-out link of ADR 182).
- **Where a play goes**: Play, Add, Play next and Shuffle all go to the remembered Play on… device through
  `PlaybackQueue.destination` (ADRs 152, 169); Play next is placed by `placingNext` (ADR 195).
- **Downloads**: ⬇️ asks through `DownloadChooser` (ADR 77); every delete goes through `LockingDownloadManager` (ADR 75).
- **Lists**: the header is `SortAndFilterSheet` + `TitleAndActions` (ADR 66) and slides away with
  `Modifier.scrollsAway()` (ADR 198); a page opened over a list uses `ListThenDetail` (ADR 88); every search box offers
  its recent searches (ADR 83).
- **Rows and item pages**: the follow mark comes from `FollowedSources` (ADR 22), the facts from `itemFacts` (ADR 65),
  views and dates on every item (ADR 192).
- **The player**: `SeekSteps` (back 10 s, forward 30 s) on every player (ADR 164); `KeepScreenOnForVideo` (ADR 161);
  Listen and Watch follow the picture (`Picture`), not the pillar (ADR 70).
- **No UI work per playback tick** (ADR 61): screens read derived playback fields, players go through `SteadyPlayback`
  with the position in `LocalLivePosition`, per-row states are a `State<Map>`, nothing animates unseen.
- **Kinds**: music and audiobooks are `MediaContentKind`s with their own rules (an audiobook never skips silence; music
  is never passed over as finished, rewound on resume or ended by a silent tail). Check the kind before adding behaviour.
- **Pi targets**: a new protocol is one more `CastControl` behind the one cast player and `CastStreamServer`
  (ADRs 156, 174), or a `TargetPlayer` when the device is handed an id (ADR 187); power is `DevicePower` (ADR 177); a
  device's colour is `TargetColours.of` (ADR 170); the sleep fade is `SleepFade`, on the phone and on a target (ADR 166).
- **AI calls** go through the Pi's AI service (`totum ai-serve`, Haiku only; ADR 113), and a new caller joins its
  tools-per-caller table (ADR 121).
- **Side quests** build on the reminder kit (`:lib:reminders` + `app/…/reminders/kit/`, ADR 17) and alone use the laurel
  gold `LocalLaurel` (ADR 34). **Stats** are an append-only log (`:lib:stats`), not Room (ADR 44).
- **Dependencies** are Renovate's (ADR 41); the PRs it holds (majors, Kotlin, AGP, Chaquopy, Media3 `needs-soak`,
  FFmpeg/QuickJS `needs-manual-rebuild`) are looked at at the start of every batch.

## Build & test (Totum)

```bash
./gradlew assembleDebug          # build debug APK
python3 tools/ci/preflight.py    # what the Gradle gate CANNOT see — run before every push
./gradlew detekt                 # with preflight, the only checks run before a push; CI runs the rest
./gradlew detekt cpdCheck lint test koverVerify assembleDebugAndroidTest   # the full gate, = CI's "Quality gates + build"
./gradlew :app:assembleDebug     # the gate does NOT rebuild app-debug.apk: run this before installing after it
./gradlew connectedDebugAndroidTest  # instrumented tests (device/emulator needed): NEVER on the signed-in emulator, hard stop 1
./gradlew :core:sound:test -Ptotum.audioQualityTests   # skip-silence + boost on real speech (needs ffmpeg)
```

**Quick checks locally, the rest in CI** (Dewi, 2026-11-04: *"quick checks locally, then the rest in the CI"*). Before a
push run only preflight, `./gradlew detekt` until clean, and the tests you wrote or changed (a bug fix's test seen red
first); then push and **watch CI on that commit to its end** (every workflow it triggers, not just the release), fixing a
red run with the very next push. Kept local because CI cannot do them: the soak (real YouTube, signed in, home network)
and looking at a changed screen.

- **`preflight.py` is part of the gate, not an extra** (Dewi, 2026-08-18: *"shift left to catch stuff faster"*): it catches
  what `detekt lint test koverVerify` cannot (a live test missing from the CI exclusion list; a shell variable that does
  not survive `android-emulator-runner`, which runs **each line** of `script:` in its own shell). Run
  `tools/ci/install-hooks.sh` once per clone (`core.hooksPath -> .githooks`) so it runs on every `git push`; the hook is
  deliberately **only** the sub-second checks (`--no-verify` bypasses it).
- The live YouTube instrumented phase runs **nightly and by hand** (`gh workflow run ci.yml --ref main`), not on push.
- `-Ptotum.exsurgeTests` does not ADD Exsurge's tests, it RESTRICTS every module's test task to them: run it as its own
  command (with another module's `test` it reports "0 tests", which reads as a pass; `--tests '*ExsurgeControllerTest'`
  without the flag runs none and still succeeds). Any change under `exsurge` gets `./gradlew test -Ptotum.exsurgeTests`
  before the push.
- The audio-quality suite is excluded from `test` and runs in CI only when a push touches the silence/boost code
  (`.github/workflows/audio-quality.yml`): run it yourself when you change that code, and name any new source file there
  so the workflow's `paths:` match (preflight fails otherwise).
- **Suppressing a warning is Claude's call, case by case** (Dewi, 2026-10-21) — detekt, lint, compiler or any other: when
  a rule fights the code's shape (a binary-format parser full of offsets, an adapter whose size is its interface, a
  `Player` wrapper that is all overrides, a wake lock held across calls by design), suppress it on that declaration and
  say why in the commit message; when it points at a real smell (a long method doing several things, a tangled condition),
  fix the code.
- detekt autocorrects and still fails the run that found the issue, per module and fail-fast: rerun before editing (a
  build that touched N modules can need N reruns); only a rule it cannot autocorrect (`LongMethod`, `TooManyFunctions`,
  `MaxLineLength`) needs a hand.
- Verify real flows on a device, not just via tests (the RSS bug passed every JVM test). **A Compose change is not
  verified until the screen has been LOOKED AT**: screenshot it on the emulator (`adb exec-out screencap -p`) before
  calling UI work done.
- **Performance:** the embedded Python engine is never started AT launch (warmed 10 s after, in the background, never under
  instrumentation; ADR 11): keep it that way. Release is **arm64-v8a only + R8** (~33 MB; `-PemulatorAbis` adds x86_64).
- JDK 21 is at `/home/dewi/code/jdk/`; Android SDK at `/home/dewi/code/android-sdk` (see `local.properties`, not committed);
  the `android` CLI (`~/.local/bin/android`) does emulators, screenshots, layout inspection, docs search.

### Before shipping a playback or extraction change: soak it against the build before

Dewi, 2026-10-07: *"are you testing thoroughly enough on the emulator before u ship? maybe not?"* — no. For a batch that
touched extraction, resolving, playback, the queue or downloads, the soak runs **once at the end of a batch** (Dewi,
2026-10-23), against the release before the batch, in parallel with the Opus review; each feature is pushed as soon as its
gate is green and it has been checked on the emulator. A regression the soak finds is fixed with a test seen red first and
pushed straight away. **Read `docs/how-to/soak.md` in full before running one**: it has the commands, and the traps that
have each produced a false result. The rules: build the previous build **fresh from the previous release's tag** into a
directory named after it, soak both with `tools/emulator/soak.py run` (three passes), then `soak.py compare before.json
after.json`: more failing passes, a median start 1.5× + 2 s slower, or a lower quality is a regression and blocks the
push; put the table in the commit message. One pass is NOT enough, and a gap of a few failures between builds is noise
until a third run repeats it. A change to how the queue hands over also gets a queue soak (`soak.py queue`). Before every
run: Exsurge and Daily alarms OFF for both builds, queue cleared or queue auto-download off, the app launched, the laptop
idle. A soak cannot prove the phone will behave: one tap proves a mechanism, never the app.

### Room schema changes

A schema bump needs the whole `:core:database` instrumented suite, not just its migration test (add the new column to
`RollBack.kt` in androidTest, or `VideoIdMigrationTest` and `DownloadDatesTest` go red in CI), and it blocks the baseline
soak (Room has no downgrade path; roll the DB back in place). Amending the body of a migration a dev device has already
run (fine while unpushed) leaves that device un-openable; the fix keeps the sign-in: force-stop, then `run-as
com.dewijones92.totum rm -f databases/totum.db*`. The crash can be invisible: the pre-rename `com.dewijones92.uniapp` is
still installed on `totum-api35` and the launcher falls through to it, so check `adb shell pidof com.dewijones92.totum`
before believing any screen. Recipes: `docs/how-to/room-schema.md`.

### The emulator: `totum-api35`, and its YouTube sign-in is perishable

Hard stops 1 and 2 come first. Recipes and history: `docs/how-to/emulator.md`.

- The AVD is **`totum-api35`** (API 35, x86_64): the orchestrator boots it with `-gpu swiftshader_indirect`, the isolated
  **36.6.11** binary, in its own `systemd-run --user --scope` (the command and the reasons are in the how-to).
- **The sign-in is perishable.** Do not trust any sentence saying it is signed in: **check** that `run-as
  com.dewijones92.totum ls -l shared_prefs/` shows a non-empty `youtube_account.xml`, before and after anything drastic.
  Lost? `tools/emulator/restore-youtube-signin.sh` restores it in ~2 s from
  `~/.credentials/totum-emulator/youtube_account.xml` (600, outside the repo; a real OAuth credential); ask Dewi for a new
  code only if the restore fails. `SignInOnThisDeviceTest` returns at once when signed in, the cheapest check; it is
  also how to sign in without the UI (watch logcat for `dewidebug signin code`), after which back the token up again.
- To reproduce a report with the phone's own queue and settings: `tools/emulator/load-state.sh [dump]` (ADR 159).

## Architecture (rules; modules and seams are in `docs/architecture.md`)

Package root `com.dewijones92.totum`; dependencies point inward. Only `:app`, `:core:database`, `:core:playback` and
`:lib:ytdlp-chaquopy` are Android modules; every other module is pure JVM, where an Android dependency is a compile
error, and `explicitApi()` stays on where it is on. Business logic lives in `:core:data`, testably, off Android;
`:core:database` is the only place entities meet domain types; `:lib:ytdlp` is deliberately independent of
`:core:domain`. `:core:database` and `:core:playback` are Kover-exempt adapters. `:core:video`, `:core:cast` and
`:core:sound` are shared with the Pi's `:cli`, so the Pi runs the phone's own code (cast and sound: ADR 119).

- **One answer, in one place.** "How do I play this, right now?" is `routeNow` in `:core:domain` (`PlaybackQueue.route` is
  its only caller); "is this a video?" is `MediaItem.pillar` / `PlayHandle.pillar`; "where do the bytes come from" is
  `PlayableItem.fetchUrl`; skip segments are enforced only in `Media3PlaybackController`'s position ticker; pillar routing of
  downloads lives only in `AppContainer` (`RoutedDownloadStrategy`, an exhaustive `when`). One deliberate asymmetry, Dewi's
  call: an **audio-only** copy of a video does not stand in while you are *watching*, only when listening or offline.
- `PlaybackController` exposes `state` (how things ARE) and `events` (what HAPPENED): if you are asking "has X changed?",
  you are on the wrong flow (`docs/architecture.md`).
- Manual DI: `AppContainer` wires the graph, construction is code, errors are compile-time; no Hilt/Koin. detekt, the lint
  policy and Android build defaults apply from the root build: never configure them per module.
- **Chaquopy:** exactly ONE module per app may apply the plugin; the build-host Python minor version must match the target
  (3.12); it is not configuration-cache compatible. ffmpeg **and ffprobe** are bundled (`libffmpeg.so`/`libffprobe.so` in
  `app/src/main/jniLibs/<abi>`, ~7 MB each per ABI): the tracked binaries are not a mistake.
- **Cleartext HTTP is deliberately permitted** (network_security_config): podcast enclosures are often plain http. Same
  policy as AntennaPod. `POST_NOTIFICATIONS` is requested once as the shell composes, never at first play (the dialog
  releases the video surface). Credentials never reach logs: `:lib:innertube` token types redact themselves in `toString()`.
- Exsurge deliberately breaks the shared seam in two places: its voice clips play on their own `MediaPlayer` (alarm sounds,
  never media; ADR 3) and its state is not in Room (ADR 2); it pauses/resumes playback only through
  `PlaybackController.setPlaying` and `PlaybackInterruption` (ADR 4).
