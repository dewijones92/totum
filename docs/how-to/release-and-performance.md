---
title: Releases, tags, CI cancellation, signing keys and measured performance
kind: reference
updated: 2026-11-28
---

# Releases and performance

The rule itself is in `CLAUDE.md` (hard stop 3, "Repo, signing, releases" and the Performance bullet of Build & test), which every session loads and which wins if the two ever disagree; change the rule there and here in the same commit. This file is the full account: the procedure, the numbers and the incidents that earned it, moved from `CLAUDE.md` at commit `698933d9` on 2026-11-28.

## Remote, releases and signing

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
  it: the tip is a superset. Learned 2026-07-25, at the cost of the Totum APK. A newer push cancels the older
  run too, but the release is published as soon as the build job is done, so what gets cancelled is the later
  instrumented tests, not the APK (2026-10-15: two "cancelled" runs had both released). Check
  `gh release view <tag> --json targetCommitish` before concluding a fix did not ship. **But the build job
  ("Quality gates + build") takes ~17-19 min and publishes only at its end, so pushes closer together than that
  release nothing** (2026-10-23: four pushes ~15 min apart, three cancelled builds, no release for an hour while a
  crash fix waited). Leave ~20 min between pushes, or batch commits into one push. Release signing key lives in
  three places, never this repo: locally at `/home/dewi/code/dewiuniapp-signing/`,
  in this repo's Actions secrets (CI signing; write-only), and backed up in
  the PRIVATE repo `dewijones92/uniapp-signing-backup` (survives laptop
  loss). versionCode is `100 + run number`, so it only ever increases.

## Performance (measured 2026-07-12, API-35 emulator)

- **Cold start** ~1.5s warm / ~3.3s first-ever. The embedded Python engine is
  never started AT launch, so startup pays nothing for it — keep it that way. Since
  2026-10-06 (Dewi's choice, ADR 11) it is warmed 10 s AFTER launch in the background
  (`TotumApplication.warmTheEngineSoon`, ~1.5 s on the emulator), so a session's first
  video does not pay ~2.7 s for it; never under instrumentation (`TotumTestRunner`).
- **Memory**: ~115MB idle; ~196MB once the Python interpreter is resident.
  Reasonable for an embedded CPython; watch it if it climbs.
- **APK**: release is **arm64-v8a only + R8 (minify + resource shrink)** →
  **~33MB**, down from 94MB. Debug stays multi-ABL/unminified for the emulator.
  Build a release for the emulator with `-PemulatorAbis` (adds x86_64).
- R8 verified end-to-end on-device (podcasts, Room, Media3, Chaquopy/Python
  search all survive minification). App keep-rules: `app/proguard-rules.pro`
  (kept minimal — library consumer rules handle Room/Media3/Chaquopy/Compose).
