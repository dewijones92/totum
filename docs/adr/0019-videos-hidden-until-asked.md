---
title: The Videos feed is hidden until asked, and hides again when you leave the tab
kind: adr
status: accepted
updated: 2026-10-07
---

# 19. The Videos feed is hidden until asked, and hides again when you leave the tab

- Status: Accepted
- Date: 2026-10-07

## Context

Dewi, 2026-10-07: *"whenever we are on the videos tab I want a button to say show videos, cuz when I
am dopamine fasting I don't want to be shown new stuff"*. He chose, from options put to him:

- hide **the whole feed**: video rows, the channel avatar strip, the progress chips and the filter
  toggle. The title, the feed chips (Home / Subscriptions / Watch Later / History, groups), sort and
  the new-uploads bell stay;
- once shown, it stays shown **until you leave the tab**, the strictest of the three offered;
- a **setting, on by default** ("Hide videos until asked" in Settings › Videos tab).

He did not answer whether Podcasts, Shorts or the bell's count should be hidden too, so only the
Videos tab is gated, which is what he asked for.

## Decision

- `AppPreferences.Settings.feedHiddenUntilAsked`, default `true`, persisted, backed up, and written to
  diagnostics as `settings.feedHiddenUntilAsked`.
- `ui/common/FeedGate.kt` holds the gate: `rememberFeedGate(where, hideUntilAsked)` and
  `ShowWhenAskedButton`. The "shown" flag is a plain `remember` in `VideosScreen`, not `rememberSaveable`:
  `AppShell` restores saveable state when a tab comes back, which would defeat "until you leave the
  tab". The screen survives its own overlays (channel, playlists, notifications) and the shell's (the
  full player, the Shorts reel) and rotation (the activity handles configuration changes), so none of
  those hide the feed again.
- While hidden the feed does not page (`LoadMoreUnlessFiltered` is disabled). It still loads its first
  page, so pressing the button shows it at once.
- The gate is in `ui/common`, not `ui/videos`, so the Podcasts tab or the Shorts reel can use the
  same gate if Dewi wants them hidden too.

## Consequences

- Every launch, and every return to the tab, shows the button first. Leaving the app for the home
  screen does not hide the feed (the screen stays composed); a process restart does.
- `dewidebug [feed-gate]` lines say when the tab was entered hidden or shown, when Show was pressed,
  and when leaving re-hid it; the `videos-screen` place line carries `feedHidden=`.
- Covered by `VideosHiddenUntilAskedTest` (hidden, shown on press, hidden again after another tab,
  shown straight away with the setting off).
