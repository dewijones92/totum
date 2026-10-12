---
title: Out-of-date banner
kind: feature
status: built — on main once pushed; not yet seen on the phone
area: ui/diagnostics
updated: 2026-10-12
---

# Out-of-date banner

When the installed build is older than the newest GitHub release, every tab shows a red banner
above its content: **Totum is out of date**, the installed and latest versions, and **Update**
(opens Obtainium, or the release page if Obtainium is not installed). Decision and reasons:
[ADR 29](../adr/0029-an-out-of-date-build-says-so-on-every-tab.md).

## Seam

- `core/data/…/update/AppVersion.kt`: numeric `0.1.N` versions; anything else is not a version.
- `core/data/…/update/LatestRelease.kt`: `ReleaseSource` port and `GitHubLatestRelease`.
- `core/data/…/update/AppUpdateCheck.kt`: the verdict (`UpdateStatus`), the 30-minute loop, and the
  report keys.
- `app/…/ui/common/OutOfDateBanner.kt`: the banner, wired as `AppShell`'s Scaffold `topBar`.
- Started in `TotumApplication.onCreate`; built in `DefaultAppContainer.appUpdate`.

## Logs and reports

- `[update] checked (<reason>): installed=… -> OUT OF DATE: v… is out (published …)`, or
  `up to date`, `unknown: …`, plus `[lookup failed: …]` when GitHub did not answer.
- `[update] update tapped -> opening Obtainium | opening <release page>`.
- Report state: `update.installed`, `update.status`, `update.checkedAtMs`, `update.lastProblem`.

## Tests

- `AppUpdateCheckTest` (JVM): numeric ordering (0.1.99 < 0.1.100), dev builds never asking, older /
  same / newer installs, a failed lookup keeping the verdict, report keys, GitHub reply parsing.
- `OutOfDateBannerTest` (instrumented): the banner on every tab when behind; absent when current.
