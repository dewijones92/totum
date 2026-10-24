---
title: Bottom tabs should remember where you were
kind: todo
status: shipped
area: navigation
priority: high
requested: 2026-07-28
updated: 2026-10-24
---

# Switching tabs threw away where you were

**Ask (Dewi, 2026-07-28):** "you know the bottom buttons? 'videos'/'podcasts'/'queue' etc
— when I switch between them it resets where I am."

Each bottom-nav destination should keep its own state while you are away from it: how far
you had scrolled, and anything you had navigated *into* from that tab. Before this, leaving a tab
and coming back dropped you at the top of a freshly-built screen — so a scroll through a long
subscriptions feed was lost to a glance at the queue, which made the tabs feel unsafe to
use.

The expected behaviour is what every mainstream app does: tabs are parallel stacks, and
switching between them is suspending one and resuming another, not restarting it.

## Two distinct things to preserve

1. **Scroll position** within each tab's list — needs the `LazyListState` to survive, which
   it does not when the composable leaves composition and its ViewModel/state is rebuilt.
2. **Navigation depth** within a tab — if you were three screens deep in Podcasts (feed →
   episode → description), Videos and back should return you there, not to the root.

The second is the bigger design question and probably decides the first: it depends on
whether each tab owns a nav stack, or whether the shell keeps one stack and tabs are just
roots. Worth settling deliberately rather than patching scroll state onto the current
arrangement.

## Where it lives

`AppShell` — the one place bottom navigation is defined, so the fix is a single seam for
all pillars, not per-tab handling. Whatever mechanism preserves state must apply to every
destination automatically; a tab that has to opt in is a bug waiting to happen when a
fourth is added.

## Watch out for

- State kept alive for every tab forever is a memory question — bounded, but worth
  measuring against the ~115MB idle baseline.
- The queue tab arguably *should* jump to the current item rather than restore scroll;
  confirm with Dewi rather than assuming symmetry.

## Shipped (2026-07-28)

Both halves, in `8290161`.

`AppShell` swaps destinations through `rememberSaveableStateHolder` keyed per destination —
wrapped around the whole `when`, so every tab gets it and a new one cannot forget to opt in.
That restores scroll position and anything already held in `rememberSaveable`.

It was not enough on its own: sub-navigation was in plain `remember`, which saves nothing.
Library's playlists/history/account flags and its open playlist, Podcasts' open feed, and
Search's browsed channel are all saveable now. The last two needed a saver, since a domain
object cannot go in a Bundle — `ui/common/MediaSourceSaver.kt` serves both pillars from one
encoding, because a channel and a feed differ only in which URL they carry.

Verified on a release build: Library → Playlists → Videos → Library landed back **inside**
Playlists. Rotation and process death benefit for free.

The queue-tab question raised above was not resolved — it still restores like the others
rather than jumping to the current item. Left until it actually annoys someone.

## Still not confirmed fixed (2026-07-28)

Dewi reported it STILL not staying put after all of the above, so this is open. Two things
came out of chasing it, both worth keeping.

**A fourth screen was missed.** `VideosScreen` held its overlay navigation — channel,
playlist, playlists list, notifications — in a plain `remember`. Same bug, fixed in three
screens and missed in the fourth, because "the shell's state holder handles it" was the
wrong mental model: the holder restores what is *saveable*, and a holder full of
`mutableStateOf` is not. `VideosNav` moved to its own file with a `Saver` (fixed-width
encoding, so a missing thumbnail cannot shift a playlist title into the channel slot).

**Guessing from the code was wrong twice.** The plausible culprits — a non-saveable
`LazyListState`, a flow dropping its value on `WhileSubscribed` — were both innocent: the
scroll state IS saveable, and the round trips in the report were 0.5s, well inside the 5s
timeout. So the answer is instrumentation, not theory: `TrackPlace` logs each screen's
position on entry and exit, and `TrackedViewModel` logs construction. That already ruled
out one whole class of cause — `videos created` appears once across four tab switches, so
view-model recreation is not it.

Waiting on a report from 0.1.177+ to say which of the remaining candidates it is: overlay
state lost, scroll not saved, or scroll saved but applied to a list that has not arrived.

## Within a tab (2026-10-24)

Report 0.1.709: search results came back at the top after "go to channel" and back. Tabs kept their place; a page
opened *inside* a tab did not, because the screen returned early with the page in place of its list. Now every such
page goes through `ListThenDetail` ([ADR 88](../adr/0088-a-page-opened-over-a-list-keeps-its-place.md)). Still open:
leaving picture-in-picture rebuilds the shell, so every tab comes back at the top.
