---
title: Every new-content notification can be switched off, per group and per kind
kind: adr
status: accepted
updated: 2026-11-04
---

# 118. Every new-content notification can be switched off, per group and per kind

- Status: Accepted
- Date: 2026-11-04

## Context

Dewi, 2026-11-04: *"for group X, I want notifications, for group B, I don't want notifications … Also, the
subscription notifications I get … you have new items in your subscription. I want to be able to turn them off."*
Group news (ADR 95) notified every new video of every group, and the six-hourly subscription check notified every
YouTube subscription upload and podcast episode, with no setting for either.

## Decision

- **Per group**: a 🔔 in each group's page header. The quiet set is `quietGroups` in the app settings (group ids), so
  a new group starts with notifications on, as before. A quiet group is still checked and still counts and marks its
  news; it only skips the post, and its videos are recorded as announced, so turning it back on never posts a backlog.
- **Per kind of subscription**: Settings → Subscriptions → New-upload notifications (YouTube) and New-episode
  notifications (podcasts), filtered by the source's kind in one place (`List<SourceUpdate>.wanted`) inside
  `NewContentNotifier`, so the background worker and Check for new content cannot disagree. Filtered-out sources are
  marked seen, for the same no-backlog reason.
- All three are in the Drive backup with the other settings.

## Consequences

- Android's own per-channel switches still work as well; these are the app's, kept in its backup and per group,
  which one Android channel for all groups could not do.
- The Library group tile does not show the bell; only the group page does.
