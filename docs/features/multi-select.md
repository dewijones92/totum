---
title: Multi-select on every list
kind: feature
status: shipped
area: ui
updated: 2026-09-24
---

# Multi-select on every list

Dewi, 2026-09-24: *"did we do multi select on all lists? if not then please do it"*. It did not exist.
His choice when asked: **long-press selects** (Gmail/Photos style); the ⋮ button still opens a row's
own menu, so nothing was lost.

## How it behaves

- **Long-press** a row to start selecting; **tap** rows to add or remove them; the ✕, **Back**, or any
  action ends it. A bar above the list says "N selected", offers **Select all** (what the filter shows,
  added to what is already chosen) and a row of action chips. The ⋮ and download controls give way to a
  checkbox while selecting, so nothing fires by accident.
- **Media lists** (Videos feed, Podcasts, a podcast's page, a channel's Videos / Shorts / Search tabs, an
  account playlist, New uploads, History, Library downloads, a local playlist, Search song and video
  results, Queue): Play next · Add to queue · Add to playlist · Download (if any is not on disk) ·
  Delete downloaded file (if any is) · Mark played · Mark unplayed. Play next and Add to queue keep the
  list's order.
- **Per list:** Queue adds Remove from queue, Move to top, Move to bottom (order kept); a local playlist
  adds Remove from playlist.
- **Non-media lists:** local playlists — **Delete**; All subscriptions — **Unsubscribe** (both pillars;
  YouTube channels are unsubscribed on the account). Both **ask for confirmation**.
- Not selectable: the channel Playlists tab and account playlists list (no action applies to many), the
  Related strip in the player, the groups and add-to-playlist pickers.

## The seam

`ui/common/Selection.kt`: `Selection` (saveable set of ids), `SelectableList` (bar + Back + provides
`LocalRowSelection`), `SelectableMediaList` (the one-statement media version), `mediaBulkActions()`
(built from the app-wide `ItemActions`), `selectableClicks` / `SelectionCheckbox` / `orSelected` shared by
every selectable row type. `MediaItemRow` picks selection up from the composition, so no media screen
wires its rows. The add-to-playlist picker now takes many items (`rememberPlaylistPicker`).

## Found while testing it

- **The bar covered the top rows** when drawn over the list; a tap meant for the first row hit the bar.
  It now sits above the list.
- **Holding the queue's drag grip selected the row** (the grip drags on movement, so a still hold was the
  row's long-press) and swapped the grip for a checkbox mid-drag. The grip now reports itself held
  (`ReorderState.gripHeld`) and the queue vetoes the row's long-press while it is
  (`LocalLongPressHeldElsewhere`). `QueueRowDragTest` caught it at CI geometry.

## Diagnostics

`[select] <list> selection started` · `select all -> N selected` · `"<action>" on N item(s)` ·
`selection of N cleared (<why>)` · `long-press on a control, not selecting` ·
`[subs] bulk unsubscribe: N (shows=… channels=… channelsNotWrittenToAccount=…)` ·
`[playlist] added N of M item(s) to playlist …`.

## Tests

`SelectionTest` (JVM); `MultiSelectTest` (instrumented, 8): long-press/tap/toggle, Back, add-to-queue and
play-next order, queue move-to-top and remove, select-all respects the filter, playlist delete and
unsubscribe both confirm (cancel does nothing), holding the grip does not select. The ordering, the two
confirmations and the grip veto were each run with the fix undone and failed. Whole instrumented suite
(164) green at CI's 320×640 dp geometry. Checked by hand on `totum-api35`: two episodes → Add to queue →
both in `queue_items` in list order; unsubscribe dialog shown and cancelled on the real account.
