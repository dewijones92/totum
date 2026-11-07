---
title: Licence, and reporting when you are not signed in to a home server
kind: feature
area: publishing
status: built 2026-11-07, Batch A (tests owed)
updated: 2026-11-07
---

# Licence, and reporting when you are not signed in

Decision: [ADR 146](../adr/0146-gpl-3-and-reports-go-to-the-pi-only-when-signed-in.md). Backlog item:
[todos/publish-licence-and-reports](../todos/publish-licence-and-reports.md).

## The licence

Totum is GPL-3.0 (`LICENSE`, the FSF text). `THIRD-PARTY-NOTICES.md` lists what the APK bundles and under what: FFmpeg
and FFprobe (LGPL 2.1 or later, built without GPL or nonfree options), QuickJS-ng (MIT), Chaquopy (MIT, embedding
Python under the PSF licence), yt-dlp and yt-dlp-ejs (The Unlicense). There is no root README, so no licence line to
change; the notices file is the pointer.

## What a person sees

- **Signed in to a home server** (a server is set and the device has a token): unchanged. Settings → Send diagnostics,
  the Quick Settings tile and the app-icon shortcut open the "What went wrong?" box with screenshots, and the report is
  saved and sent to the Pi.
- **Not signed in:** the same three doors say **🐙 Report on GitHub** and open two steps.
  1. The note box (no screenshots; it says they can be attached on GitHub), the last three reports opened from this
     phone with their dates, and **📂 My issues on GitHub**.
  2. **👀 This is what GitHub will be given**: the title and body exactly as they will be copied, a switch **📋 Include
     recent activity** (off; on adds up to 80 recent log lines and says how many fit), and **🐙 Copy and open GitHub**.
     That copies the body, remembers the title, and opens the new-issue form in the browser with title and body
     filled in. The person presses Submit there. Nothing is posted by the app.
- A crash on a signed-out phone is still written to disk; it is not sent, and it goes if the phone signs in later.

## What goes in, and what never does

In: the note, the app version, build, commit and build type, the device model, Android version and ABIs, and (only if
switched on) recent activity lines. Never: the install id, settings, queue, titles, the report JSON, screenshots.
Everything, including the note, passes `ReportRedactor`: the device token and Prowlarr key by value; the home server's
address in every form (its setting, the address built into the APK, `totum.` and `totumauth.` hosts, the Pi sink's
host); credentials in URLs; `token=`, `key=`, `#key=`-style parameters; `Authorization`, `X-Totum-Token`, `Cookie` lines;
bearer, Google and JWT tokens; email addresses; IPv4 and IPv6 addresses; magnet links; opaque strings of 32 or more
characters. The link is capped at 7,000 characters: oldest activity goes first, then the end of the note (marked `…`).

## Seams

`:lib:common`: `ReportRedactor`, `GitHubReport` (the draft), `HomeServerAuth.isSignedIn`/`signedIn`. App:
`diagnostics/GitHubReportSources` (facts, activity, `reportRedactorFor`), `diagnostics/OpenedReports`,
`ui/settings/ReportDialog` (the one door for the Settings row and `SendReportActivity`), `rememberSuspendCopier` in
`ui/common/LinksAndCopy`. `DiagnosticsUploader` and the screen announcer read the same sign-in.

## Diagnostics

`diagnostics` lines: `kept N report(s) on the phone: not signed in to a home server …`, `GitHub report: N chars typed;
showing the preview`, `GitHub report: copying and opening it; title … body … link … chars, activity lines K of M (asked
for=…), note cut=…` (lengths only, never the text), `remembered that a GitHub report was opened`. `screen` lines: `not
telling the Pi where the PC screen page is: not signed in …`.

## Needs Dewi

- Issues must be enabled on `github.com/dewijones92/totum` (Settings → Features → Issues).
- The sink's `/ingest` still accepts anonymous posts; making it require the token is an nginx change in `dot-files`
  that would reject every older build (see the ADR).

## Tests owed

A Compose test of the two steps and the switch; the instrumented uploader case added to `EmulatorsDoNotUploadTest`
has not been run; a device check that the link opens the form pre-filled; a check that the announce stops on sign-out.
