---
title: Search in Settings
kind: feature
status: built 2026-11-06
area: settings
updated: 2026-11-06
---

# Search in Settings

Dewi, 2026-11-06. Settings rows are composables, not data, so a search needs a list of them.

## What it does

A filter field sits under the Settings header (the same `FilterField` every list uses, so it is fuzzy and offers
recent searches, [ADR 83](../adr/0083-every-search-box-offers-its-recent-searches.md)). While it holds a query the
sections give way to the matching rows, each with its section and summary under its name. Tapping one:

- on the main page, clears the query, scrolls to the row and tints it for two seconds;
- for a row that is a page (Exsurge, Daily alarms, Import / export, View diagnostics, and the cards inside Exsurge's
  page), opens that page.

A pick is also kept as a recent search.

## The registry

`SettingsRow` (`ui/settings/SettingsRegistry.kt`) is the one list: an enum constant per row with its label, its
section, its summary, extra search words and, for a page, `SettingsSubPage`. Matching reads exactly those strings, so
a row is found by what it says, not by a second hand-kept list.

**A new setting registers itself in two places, both in the change that adds it:** a `SettingsRow` constant, and
`AnchoredRow(SettingsRow.X) { ... }` (or `Modifier.settingsRow`) around the row, which is what the scroll and the tint
attach to. A constant with no anchor opens nothing on the main page; an anchor with no constant is unsearchable.

Rows that are not always on screen (the "Choose sources" row only shows when "Every subscription" is off) are
not registered; their parent is, with their words as search words. A scroll target that never appears is dropped
after 1.5 s and logged.

## Diagnostics

`settings` lines: each search that is picked (`search "<query>" picked <ROW>: opens <PAGE> | scrolls to the row`),
each scroll (`scrolled to <ROW> and highlighted it`), and a target that was not on screen (`dropped`). The recent
store logs under `recent`.

## Tests still to write (written in part 2026-11-14: see [the tests index](../tests/_index.md))

Registry sanity (every constant has a label, section, and an anchor on the main page unless it has a page), the
fuzzy match over `SettingsRow` strings, and a Compose test that a pick scrolls to and tints a row far down the page.
