---
title: A crash screen for everyone, reports to the Pi as a setting, and a state dump with every report
kind: adr
status: accepted
updated: 2026-11-14
---

# 159. A crash screen for everyone, reports to the Pi as a setting, and a state dump with every report

- Status: Accepted
- Date: 2026-11-13
- Builds on: [ADR 146](0146-gpl-3-and-reports-go-to-the-pi-only-when-signed-in.md) (the GitHub flow and the sign-in
  rule), [ADR 90](0090-a-token-per-device-and-none-in-links.md) (device tokens),
  [ADR 53](0053-google-drive-backup.md) (the backup, and its restore merge),
  [ADR 140](0140-ask-answers-about-your-own-app-with-jq-only.md) (the Ask snapshot and the one redactor)

## Context

Dewi, 2026-11-13: a crash screen like PipePipe's for everyone; reports to the Pi optional; every diagnostics report to
bring the app's whole state, so a problem can be reproduced with his queue and settings rather than described; a button
to send that state on its own; and a way to load a dump into the emulator. He delegated the decisions to the
coordinator and agreed. A crash used to be invisible on the phone: the handler wrote a report and the process died.

## Decision

- **A crash screen at the next launch.** `CrashReporter`'s handler still writes the report first, and now also a
  `CrashRecord` (`files/last-crash.json`: time, thread, exception, message, the whole stack trace with its causes, the
  report's file name). `MainActivity` starts `CrashActivity` on a cold start when a record exists (never in a test run).
  It shows "❌ Totum crashed", when, the message and the stack trace, and: **🐙 Report on GitHub**, **📋 Copy**,
  **🏠 Send to my Pi** (signed-in phones only) and **Continue to Totum**. Continue and Back clear the record, so the screen
  is shown once. Not a separate process: a crash activity in `:crash` would run the whole `Application` again, and "next
  launch" was in the brief.
- **One GitHub flow.** The screen reuses ADR 146's preview dialog (`GitHubPreviewDialog`, now `internal`) and the
  one redactor; there is no second copy. `GitHubReport.draft` takes an optional `trace`: the copied body keeps the **whole**
  stack trace under a "Stack trace" heading, and the link keeps what fits under 7,000 characters (activity lines go first,
  then the trace is cut at a line, then the note). When the link's trace is cut the dialog says so, because the copied
  body is the one to paste. Copy redacts too: a message can carry a token.
- **Sending to the Pi is a setting.** "🏠 Send diagnostics and crash reports to my home server", on by default, shown
  when signed in. `DiagnosticsUploader.upload(becauseSomeoneAsked = false)`, the launch-time upload, posts nothing when it
  is off and says so in the log. A person tapping send (Settings' hand-send, the crash screen's button, "Send my state")
  is not automatic and goes. With it off, Settings' report row and the tile offer GitHub, as for a signed-out phone.
- **A state dump with every crash or diagnostics report.** `StateDump` (`:core:data`) gathers nothing of its own. It takes
  the Ask snapshot's inputs (`AskStateSource.inputs()`, formerly private `gather()`), puts the live queue back into the
  backup (so the backup section restores), adds every SharedPreferences file (settings, Exsurge, daily alarms, pins,
  trail and the rest) and runs the lot through `StateScrub` and the one `ReportRedactor`:
  `{format, kind, takenAtMs, appVersion, installId, backup, state, preferences}`, gzipped (about 94 KB for 800 KB on the
  emulator). The `state` section is the Ask snapshot itself: queue, history, playlists, groups, subscriptions, downloads
  (the list, not the files), progress, listens and other stats, the trail, settings and the event log. A dump follows a
  report only after the report was accepted, once per upload run, and a dump that cannot be gathered never stops a
  report being sent or deleted.
- **Credentials never go.** `youtube_account*`, `totum_visitor`, `totum_screen` (the PC screen's access key, added
  2026-11-14: it was in every dump), `totum_drive_backup`, `totum_test_holds` and
  `totum_diagnostics` are left out whole, and every string value in them is added to the redactor's secrets, so the same
  token cannot come back through an event line. Keys that name a secret (token, secret, password, cookie, refresh, api
  key…) are dropped everywhere. `StateDumpTest` seeds fake home-server, Prowlarr, YouTube access and refresh tokens, the PC screen key and a
  cookie in the preferences and in event text and asserts none appears, nor the home server's host; and on 2026-11-13 a
  real dump built by the emulator (signed in to YouTube and to the home server) was checked against the emulator's own
  four secret values: all absent.
- **"📤 Send my state to Claude"** is a Settings → Diagnostics row, shown only when signed in, that posts the same dump
  with no report. It runs as a tracked task (the progress strip) and ends with a ✅ or an honest ❌.
- **The server keeps five.** `POST /ingest/state` (`tools/crashlog-server/app/states.py`, which imports no web framework)
  refuses anything not gzip, over 32 MB packed or 256 MB unpacked, or not JSON of kind `totum-state-dump`; keeps the
  dump under `/data/states/<time>-<install>-<version>.json.gz`, and deletes all but the newest five. Reports are untouched.
  `GET /api/states` lists them and `GET /state/latest` downloads the newest.
- **Loading one.** `tools/emulator/load-state.sh [dump]` fetches the newest dump from the Pi by read-only ssh (or takes a
  file), refuses any device that is not `totum-api35`, and broadcasts to the debug-only `StateDebugReceiver`, which
  reads the backup section and calls `BackupService.restore`, the same merge Drive restore uses but with the queue. It
  switches queue auto-download off on the emulator, prints what the app logged it restored, restarts the app so the queue
  is read, and checks the YouTube sign-in file is present and non-empty before and after. A restore carries no sign-in, so it
  cannot change it. Stats, the trail, downloads and Exsurge state are in the dump for reading but are not restored.
  `DEBUG_CRASH` shows the crash screen and `DEBUG_DUMP_STATE` writes the phone-shaped dump to `files/` for inspection.

The allow-list, the five, "once per upload run", the 32 MB cap, Continue clearing the record and the auto-download switch-off
are the builder's calls, open to change.

## Consequences

- A crash is no longer silent on the phone, and a stranger's crash can be reported on GitHub with its whole trace.
- **`/ingest/state` is as unauthenticated as `/ingest`.** The brief assumed the server checks the device token; the crashlog
  sink does not (ADR 146). A state dump is far more personal than a crash report, so it should get nginx's `auth_request`
  against `totum-auth` (ADR 90) before the app is released widely. Until then the app sends the token and the server
  ignores it, and a stranger could post junk dumps (bounded: five kept, 32 MB each).
- A subscription's feed address keeps its path in the dump (identifier mode, so the backup restores), the same limit ADR
  140 states for the Ask snapshot; the dump stays on Dewi's Pi.
- Building a dump takes about 11 s on the emulator (the Ask snapshot over 2,200 listens); it is off the main thread and
  runs after the report is sent.
- The home-server setting is not in the Drive backup: it is device-shaped.

## Tests

`StateDumpTest` (core/data, 6), `GitHubReportTest` (5 new: the trace in body and link, cut from the link only, events go
first, redacted, absent when none), `DiagnosticsUploaderTest` (11: the setting off posts nothing and keeps the file, a person's
send bypasses it, signed out, test run, non-fatal reports bring no dump, an unreadable dump does not stop the report, a
refused dump leaves the report sent, send-my-state results), `CrashRecordsTest` (7), and the server's `test_states.py` (12)
and `test_state_endpoint.py` (5, in a `python:3.14-slim` container with the pinned requirements and httpx: 30 tests, all
green). On the emulator: a real uncaught exception via `DEBUG_CRASH` wrote the record and the next launch showed the
screen (screenshot checked, Continue cleared it); the GitHub preview shows the trace; a real dump was built and checked for
tokens; `load-state.sh` loaded a modified dump (a playlist the emulator lacked, a three-item queue, a changed setting),
a fresh dump read back all three, the original was loaded again, and the sign-in file was present before and after each.

## Tests still to write

A Compose test of the crash screen's buttons; an instrumented test that the real handler writes the record; nginx
`auth_request` for `/ingest/state` (in `dot-files`, the Pi's to deploy).
