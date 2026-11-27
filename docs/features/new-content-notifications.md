---
title: New-content notifications
kind: feature
area: notifications
status: shipped
updated: 2026-11-27
---

# 🔔 New-content notifications

Two kinds, each with its own switch ([ADR 118](../adr/0118-every-new-content-notification-can-be-switched-off.md)):

- **Subscriptions** (`NewContentCheck` → `NewContentNotifier`, every 6 h and Settings → Check for new content): one
  "YouTube subscriptions · N new" notification and one per podcast with new episodes, under a summary. Settings →
  Subscriptions has **New-upload notifications** (YouTube) and **New-episode notifications** (podcasts), both on by
  default. A switched-off kind is still marked seen, so switching it back on does not post the backlog.
- **Groups** (`GroupNews`, hourly; [ADR 95](../adr/0095-groups-say-whats-new-and-live-in-the-library.md)): one
  notification per new video in a group. Each group's page has a 🔔 in its header; off means no notifications from
  that group, while its "🆕 N new" count, markers and the Library badge carry on. Videos that arrive while it is off
  are never announced later.

Both settings are in the Drive backup (`quietGroups`, `youTubeUploadAlerts`, `podcastEpisodeAlerts`).

Logs: `[content] N source(s) not notified: alerts off (youTube=… podcasts=…)` and `[group] "<name>": new "<title>" …
not notified: this group's notifications are off`.

Tests: `NewContentAlertsTest`, `GroupNewsTest` (a quiet group), `PreferenceBackupTest`, `NewContentCheckTest`.

## 2026-11-27: a group's new video is announced even if the group was opened first

Report 20261124T060317: a video went public at 06:00:27, the group was opened at 06:01:14, and the 07:00 check never
announced it. `GroupNews` now judges by video id: announced once, published after the member joined the group, within 48 h
(`[group] … announce check: read N item(s): A announced, B not announced (already announced …, no date …, published before
the group or member was first looked at …, older than 48 h …, group's notifications off …, notification could not be
posted …)`). Opening still clears "🆕 N". `group` is a kept log tag. See
[ADR 95](../adr/0095-groups-say-whats-new-and-live-in-the-library.md) and
[ADR 192](../adr/0192-one-watch-page-parse-fills-views-everywhere.md). Tests: `GroupNewsTest`.
