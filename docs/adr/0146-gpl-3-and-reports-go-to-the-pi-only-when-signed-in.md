---
title: GPL-3.0, and reports go to the Pi only from a phone signed in to the home server
kind: adr
status: accepted
updated: 2026-11-07
---

# 146. GPL-3.0, and reports go to the Pi only from a phone signed in to the home server

- Status: Accepted
- Date: 2026-11-07

## Context

Totum is being made ready for other people to install (Dewi, 2026-11-05: GPL-3.0, with notices for the bundled
ffmpeg, QuickJS, Chaquopy and yt-dlp; reports reach his Pi only from phones signed in to his home server, everyone
else reports on GitHub). Until now every phone posted crash and diagnostics reports, and the PC-screen announce, to
the Pi's sink, which is unauthenticated by design ([crash-reporting](../features/crash-reporting.md)). A stranger's
phone must not post to Dewi's server, and a stranger's report must not carry anything that identifies Dewi's server or
the stranger's accounts.

## Decision

- **Licence.** GPL-3.0-only text at the repository root (`LICENSE`, the FSF text) and `THIRD-PARTY-NOTICES.md` naming
  each bundled component with its licence and source. The ffmpeg build is configured without `--enable-gpl` and
  `--enable-nonfree`, so it is LGPL-2.1-or-later. The repository has no root README, so there is no licence line to
  update there.
- **One definition of signed in.** `HomeServerAuth.isSignedIn(host, token)` (`:lib:common`): a home server is set and
  there is a device token. The settings read it through `Settings.signedInToHome()`; the global `signedIn()` answers
  for code that has no settings (the uploader, the announcer).
- **The Pi sink is for signed-in phones, and it is the configured server's sink.** `DiagnosticsUploader` keeps its
  pending reports on the phone, logging why, when the phone is not signed in; the PC-screen announce does the same
  (twice: when it starts, and again before each post, so signing out stops a running announce). The reports stay on the
  phone (capped at 50) and go if the phone later signs in.
- **The sink is derived, never hard-coded** (review finding, 2026-11-07: the first build posted to one fixed address,
  so a stranger self-hosting `server/` would have sent their reports, token and PC-screen address to Dewi). The sink is
  `crashlog.<home server base>`, registered once beside the home host in `HomeServerAuth.use(…, sinkHost)`
  (`TotumApplication`), and `HomeServerAuth.reportEndpoint()` is `https://<sink>/ingest`, null with no home server. Both
  posts go through `ReportSink`, which sends the device token only as `HomeServerAuth.headersFor(<endpoint host>)`
  gives it, so the token reaches the configured home server's own hosts and no other; an endpoint that is not that
  sink, a signed-out phone and a phone with no home server send nothing and say why in the log. The sink's address was
  also removed from the APK's source. This is the coordinator's call; Dewi delegated it, 2026-11-06/07.
- **A signed-out phone reports on GitHub.** Settings → "🐙 Report on GitHub", and the Quick Settings tile and the
  app-icon shortcut, open the same two steps: the note box, then a preview of exactly what will be given to GitHub,
  with a switch for recent activity (off by default, because it can mention what you were playing). "Copy and open
  GitHub" copies the body and opens `github.com/dewijones92/totum/issues/new?title=…&body=…` in the browser. The
  person submits it there. Nothing is posted by the app.
- **The body is built from an allow-list, then redacted.** Facts are the app version, build, commit and build type, the
  device, Android and ABIs: never the install id, settings, queue, titles or the report JSON. `ReportRedactor` removes
  the device token and the Prowlarr key by value, the home server's address in every form the phone knows (its own
  setting, the one built into the APK, `totum.` and `totumauth.` hosts, the Pi sink's host), credentials in URLs,
  `token=`/`key=`/`#key=`-style parameters, `Authorization`/`X-Totum-Token`/`Cookie` lines, bearer tokens, Google
  and JWT tokens, email addresses, IPv4 and IPv6 addresses, magnet links and any 32-character-or-longer opaque string.
  What the person typed is redacted too. A link is capped at 7,000 characters: the oldest activity lines go first, then
  the end of the note (marked), so the browser never gets a link it truncates.
- **An in-app list of what you opened.** The note box lists the last three reports opened from this phone (date and
  title) and a button to your issues on GitHub (`author:@me`). The app cannot know whether a report was submitted, so
  it says "opened".
- **No screenshots in the link.** A link cannot carry an image; the box says they can be attached on the GitHub page.

Dewi's own words cover the licence, the notices and the split between the Pi and GitHub. The rest is the builder's
call, open to change: the allow-list and what it holds, activity off by default, the 7,000-character cap, keeping
unsent reports on the phone, the "opened" list, and leaving the server's `/ingest` alone.

## Consequences

- A phone that never signs in never posts anything to the Pi. A report written by a crash is still written to disk and
  waits there.
- The sink's `/ingest` is still unauthenticated on the server side: this change makes the app send the token, not the
  server require it. Requiring it needs the nginx `auth_request` rule in `dot-files` and the Pi deployed first, which
  would reject every older build; it is a separate change and not made here.
- The built APK still contains the home-server domain (`BuildConfig.HOME_SERVER`), as before; reports never repeat it.
- GitHub issues must be enabled on `dewijones92/totum` for the button to land on a form.

## Tests

`ReportSinkTest` (the token reaches the configured sink only; another endpoint, signed-out and no-server send nothing),
`HomeServerAuthTest` (a stranger's base gives a stranger's sink and never the fixed one), `ReportRedactorTest`, `GitHubReportTest` (including: no token, key, home-server address, email or network address
survives in the title, body, link or decoded link), `HomeServerAuthTest` and `ReportSourcesTest` (every address
the phone knows the home server by). `EmulatorsDoNotUploadTest.aPhoneNotSignedInToAHomeServerKeepsItsReportsAndSendsNothing`
(instrumented, not run).

## Tests still to write

A Compose test of the two-step GitHub dialog (preview shows the redacted body, the switch adds the activity block, the
button copies and opens); a device check that the link opens the issue form with the title and body filled in; a test
that the announce stops after signing out.
