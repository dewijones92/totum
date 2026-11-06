---
title: The banner can install the update, and the first launch after one says what changed
kind: adr
status: accepted
updated: 2026-11-06
---

# 130. The banner can install the update, and the first launch after one says what changed

- Status: Accepted
- Date: 2026-11-06

## Context

Dewi, 2026-11-06: the Pixel 4a was three releases behind. [ADR 29](0029-an-out-of-date-build-says-so-on-every-tab.md)
made a stale build loud but left the fix to Obtainium, which checks in its own time, or to a browser tab on the
release page. Two wishes followed: update from the banner itself, and after an update see what changed.

`Release` knew only the tag, the page and the publish time. The APK's address and the notes both live in the same
GitHub reply that was already being parsed, and the release body held no change list at all: it was a fixed
paragraph about installing.

## Decision

- **One extended `Release`**, read once from GitHub's reply: `apk` (the asset named `totum.apk`: address and size)
  and `notes` (the body). `ReleaseSource` gains `byTag`, which asks `releases/tags/<tag>` for the installed build's
  own notes; the default answers "unavailable", so a lambda source still works.
- **Download and install from the banner.** Update stays the banner's one button when the release has no APK or the
  container has no installer (previews). Otherwise it offers **⬇️ Download and install**, with Obtainium (or the
  release page when it is not installed) kept beside it.
  - The download is `HttpDownloadStrategy` (the podcast enclosure fetch) given a synthetic item, so the `.part`
    file, Range resume, the HTML-is-not-media refusal and progress are the code that already exists, not a second
    copy. It goes to `cacheDir/app-update/totum-<version>.apk`, shows in the progress strip as a task with a bar, and
    in a notification on the downloads channel. Cancelling keeps the part file; asking again resumes.
  - **Checked before Android sees it.** `PackageManager.getPackageArchiveInfo` with signing info reads the file; the
    pure `judgeApk` refuses it unless the package name is Totum's, it is signed, its signing certificates equal the
    installed app's and its version name is the release's. A refused file is deleted and the banner says why.
  - **Installed through Android's own installer**: an `ACTION_VIEW` on a `FileProvider` content URI
    (`<applicationId>.updates`, `cache-path app-update/`), with `REQUEST_INSTALL_PACKAGES` declared. A
    `PackageInstaller` session would give status callbacks but needs a receiver and a foreground hand-over for what
    Android's screen already does. The first time, Android has not allowed installs from Totum: the banner opens that
    setting, says so in one line, and the verified file waits for a second tap on **📲 Install**.
  - Old update files are removed when a newer one is started and when the app is up to date.
- **What's new.** `WhatsNew` compares the installed version with the last one it showed (a small preferences file).
  Different, and not a fresh install, fetches that build's notes by tag and shows **🆕 What's new in 0.1.N** once,
  as a dismissible dialog. A build with no stored version counts as an upgrade unless the package's install time
  equals its update time, so Dewi's first update after this ships still shows the card, and a fresh install never
  does. A failed fetch tries again at the next launch; a release with no list is marked seen.
- **The notes are written by CI.** The release step lists the first line of every commit since the previous release
  (GitHub's compare API, so no deeper checkout) under a `## What's new` heading, capped at 40. `whatsNewLines` reads
  only that section, drops any `Co-Authored-By`, `Claude-Session` or `Signed-off-by` line, and turns
  `feat(scope): text` into `✨ Scope: text` (🐛 fix, ⚡ perf, 🧹 refactor, 🧪 test, 📝 docs, 🔧 chore/ci/build/style,
  • for anything else). Releases made before this change have no section and show no card.
- Reports carry `update.install` (idle, downloading n%, ready, refused with the reason, failed) and
  `whatsNew.lastSeen` / `whatsNew.outcome`; the log lines are tagged `update` and `whatsnew`.

## Consequences

- The card shows only the installed release's own commits. Updating across several releases lists the last one's
  changes, not the ones between.
- `REQUEST_INSTALL_PACKAGES` is a new permission. Android still asks once, in its own settings screen.
- Signing-key rotation would make the check refuse an update that Android itself would accept; this app has one key,
  kept in three places.
- The heading text lives in two places, the workflow and `WHATS_NEW_HEADING`; a mismatch shows as "no list" in
  `whatsNew.outcome`.
- Not yet run on a device or against a real CI release; the first release built from this change is the first with a
  list, and the first install through the banner is the first real test of the signature check and the permission
  screen.
