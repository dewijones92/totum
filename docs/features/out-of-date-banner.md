---
title: Out-of-date banner
kind: feature
status: built — banner shipped 2026-10-12; in-app install and What's new built 2026-11-06 (ADR 130), not yet run on a device
area: ui/diagnostics
updated: 2026-11-06
---

# Out-of-date banner

When the installed build is older than the newest GitHub release, every tab shows a red banner
above its content: **Totum is out of date**, the installed and latest versions, and **⬇️ Download and install**
with **Obtainium** (or **Release page** when Obtainium is not installed) beside it. A release with no APK, or a
container with no installer (previews), keeps the old single **Update** button. Decisions and reasons:
[ADR 29](../adr/0029-an-out-of-date-build-says-so-on-every-tab.md) (the banner),
[ADR 130](../adr/0130-update-from-the-banner-and-say-whats-new.md) (installing it, and what's new).

## Seam

- `core/data/…/update/AppVersion.kt`: numeric `0.1.N` versions; anything else is not a version.
- `core/data/…/update/LatestRelease.kt`: `ReleaseSource` port (`latest`, `byTag`), `GitHubLatestRelease`, and the one
  `Release` (tag, page, publish time, `apk` address and size, `notes`) that the installer and What's new share.
- `core/data/…/update/ApkCheck.kt`: `judgeApk` (package, signing key, version) and `Release.apkDownload()`, the item
  the existing podcast fetch downloads.
- `core/data/…/update/WhatsNew.kt`: `whatsNewVerdict` (show, record only, nothing) and `whatsNewLines` (the CI
  `## What's new` section as a readable list).
- `app/…/update/AppUpdateInstaller.kt`: download, check, install (state `UpdateInstall`); `UpdateNotices` for the
  notification; `WhatsNew` and `PrefsLastSeenVersion`.
- `core/data/…/update/AppUpdateCheck.kt`: the verdict (`UpdateStatus`), the 30-minute loop, and the
  report keys.
- `app/…/ui/common/OutOfDateBanner.kt`: the banner, wired as `AppShell`'s Scaffold `topBar`;
  `app/…/ui/common/WhatsNewCard.kt`: the card, hosted by `ShellBanners`.
- Started in `TotumApplication.onCreate`; built in `DefaultAppContainer.appUpdate`.

## Update from the banner

1. **⬇️ Download and install** fetches `totum.apk` to `cacheDir/app-update/` (resumable `.part`; Cancel keeps it),
   with a bar in the progress strip and a notification.
2. The file is read with `getPackageArchiveInfo` and refused, deleted and explained unless it is Totum's package,
   signed, signed with the installed app's key and the release's version.
3. **📲 Install** opens Android's installer on a `FileProvider` URI. The first time Android has not allowed installs
   from Totum: the banner opens that setting and says so; come back and tap Install again (the file is kept).
4. Old update files go when a newer one starts and when the app is up to date.

Manifest: `REQUEST_INSTALL_PACKAGES`, a `FileProvider` (`<applicationId>.updates`, `res/xml/update_file_paths.xml`).

## What's new

After an update, once, a dialog **🆕 What's new in 0.1.N** lists that release's changes. CI writes them: the release
step lists each commit's first line since the previous release under `## What's new`, and `whatsNewLines` renders
`feat(scope): text` as `✨ Scope: text`. Never on a fresh install (install time equals update time with nothing
stored); a build older than this feature counts as an upgrade; a failed fetch retries next launch; a release with
no list is marked seen. Shows the installed release's changes only, not every release skipped over.

## Logs and reports

- `[update] checked (<reason>): installed=… -> OUT OF DATE: v… is out (published …)`, or
  `up to date`, `unknown: …`, plus `[lookup failed: …]` when GitHub did not answer.
- `[update] update tapped -> opening Obtainium | opening <release page>`.
- `[update] download: …` (fetching, already here, whole and matching, REFUSED with the reason, failed), `check:
  file=… installed=… expected=…` (the package, version and signer hashes compared), `install: …` (allow-installs
  opened, handed to Android), `tidy: …`, and `notice for v…`.
- `[whatsnew] load: installed=… lastSeen=… freshInstall=… -> verdict`, then what happened (card shown with N changes,
  recorded as seen and why, notes not fetched).
- Report state: `update.installed`, `update.status`, `update.checkedAtMs`, `update.lastProblem`, `update.install`,
  `whatsNew.lastSeen`, `whatsNew.outcome`.

## Tests

- `AppUpdateCheckTest` (JVM): numeric ordering (0.1.99 < 0.1.100), dev builds never asking, older /
  same / newer installs, a failed lookup keeping the verdict, report keys, GitHub reply parsing.
- `OutOfDateBannerTest` (instrumented): the banner on every tab when behind; absent when current.
- **Still to write** (code-first batch, 2026-11-06): `judgeApk` per refusal; `whatsNewVerdict` and `whatsNewLines`
  (Co-Authored lines, prefixes, no section); `GitHubLatestRelease` reading the asset and body and `byTag`;
  `AppUpdateInstaller` with a fake package reader; a banner test per `UpdateInstall` state; an instrumented install
  of a re-signed APK, which must be refused.
