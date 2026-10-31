---
title: A tab says what is new since you last looked at it
kind: adr
status: accepted
updated: 2026-10-31
---

# 107. A tab says what is new since you last looked at it

- Status: Accepted
- Date: 2026-10-31

## Context

Dewi: *"our bottom tabs … podcasts especially … a number that pops … to indicate, hey, there's something new
here."* The Library tab already wore a "🆕 N" badge for its groups (ADRs 94, 95); nothing else did, so new
episodes that the six-hourly refresh (`NewContentWorker`) had already fetched sat unnoticed until the tab was opened.

## Decision

- One rule for the dock (`TabNews`): a tab wears "🆕 N" for what arrived since you last looked at it, and the tab
  you are on wears none (the Library's badge now also hides while you are on the Library; its tiles say the same).
- **Podcasts:** N is the episodes published after the Podcasts tab was last opened or left (`TabSeenStore`, one
  timestamp per tab in `totum_tab_seen` prefs). Audiobook chapters never count. A tab never looked at (a fresh
  install, or the first run of this build) starts from now, so it never shows every episode ever.
- **Videos:** no badge, by choice: the Videos feed is hidden until asked (ADR 19) and a count would be the pull that
  feature exists to remove. **Music** and **Queue** have nothing that arrives by itself, so no badge.
- Logged: when a tab is looked at and what it cleared, and the count whenever it is above zero.

## Consequences

- The badge only moves when episodes are fetched: by the six-hourly refresh, pull to refresh, or the All subscriptions
  page. No extra refresh was added.
- A new tab that wants a badge adds its count to the one map in `AppShell`'s `BottomBar`.
