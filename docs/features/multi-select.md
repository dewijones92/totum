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
- **At 320 dp the Download chip is off the end of the action row**, which scrolls sideways. The bulk
  download test now scrolls to it (`SELECTION_ACTIONS_TAG`) rather than assuming a wide screen.
- **`OfflineQueuePlaybackTest` failed 2 runs in 4 on the dev emulator**, unchanged code included: a bulk
  download during hand-testing had left a real download record for its "never-downloaded" item, so the
  item played from disk instead of being skipped. The test now deletes that record itself, as it already
  did for its other item (4 of 4 after).

## Found by the Opus review

- **A bulk add-to-playlist lost items.** The picker saved on a coroutine scope that died with the dialog,
  so a many-item add to a Room-backed playlist was cancelled part-way. It now saves on the application
  scope. The test needed a store with disk-like latency to fail at all: the in-memory fake saved
  instantly and passed with the bug.
- **Queue "Select all" picked items inside collapsed groups** that were not on screen. `visible` now
  leaves hidden entries out (`GroupCollapse.hides`), and the queue offers no Add to queue / Play next
  (they would duplicate what is already there).
- **A filter that briefly matched nothing dropped the whole selection** on History, New uploads and a
  local playlist, because the selection lived inside a subtree the empty state replaced. It is hoisted
  there now.
- **A selection followed you to another source.** The Videos feed, a channel's tabs and a podcast's page
  now key their selection by what is shown, so switching feed or source starts empty.
- **Search results could hold one item twice** (song and video results), so "N selected" miscounted;
  the selectable items are distinct by id.
- **Bulk download fetched items already on disk or downloading**; it now acts only on the missing ones
  and logs how many it skipped.
- Left as they are, and noted: the ripple on a row that has just left the list; an unsubscribe that
  fails on the account after the local row has gone (it is logged by channel name).

## Found by the second Opus review (of the fixes)

- **Bulk Download skipped a video that only had an audio-only copy**, so on the Videos feed there was no
  bulk way to get the picture. Outside the queue an audio-only video copy now counts as missing.
- **Bulk queueing mirrored every item to Watch Later.** That breaks the rule `onQueuedByUser` already
  states, and that `playAll` follows: a bulk add would bury Watch Later. Only a single deliberate add
  mirrors now; a bulk one logs that it did not.
- **An empty bulk add marked the queue as touched**, which before the saved queue has loaded throws it
  away. It is now a logged no-op.
- **A podcast page whose filter hides every episode said "feed empty" and lost its filter chips**, so
  the one control that could undo it was gone (easy to hit with Select all → Mark played on Unplayed).
  The page is "empty" only when the feed is; otherwise it keeps the chips and says the filter hides
  everything.
- **Selection keys:** channel search now keys on the channel and the submitted query, and Search on
  the submitted query rather than the text in the box.
- Noted, not changed: collapsing a queue group after selecting leaves the hidden rows selected (as
  Gmail keeps a selection you scroll away from); a selection over 500 items is not saved across
  process death; while a hoisted selection's list is filtered to nothing, Back leaves the screen.

## Diagnostics

`[select] <list> selection started` · `select all -> N selected` · `"<action>" on N item(s)` ·
`selection of N cleared (<why>)` · `long-press on a control, not selecting` ·
`[subs] bulk unsubscribe: N (shows=… channels=… channelsNotWrittenToAccount=…)` ·
`[playlist] added N of M item(s) to playlist …`.

## Tests

`SelectionTest` (JVM); `MultiSelectTest` (instrumented, 12): long-press/tap/toggle, Back, add-to-queue and
play-next order, queue move-to-top and remove, select-all respects the filter, playlist delete and
unsubscribe both confirm (cancel does nothing), holding the grip does not select, adding 12 to a slow
playlist store keeps all 12, queue select-all ignores a collapsed group, bulk download fetches only the
missing item, and a filter that briefly matches nothing keeps the selection. `PlaybackQueueTest` covers bulk add-to-end and play-next order,
mirroring one but not many, and an empty add not discarding the saved queue; `GoToSourceGoesThereTest`
covers the podcast page whose filter hides everything. Each of those was run with
its fix undone and failed. Whole instrumented suite
(164) green at CI's 320×640 dp geometry. Checked by hand on `totum-api35`: two episodes → Add to queue →
both in `queue_items` in list order; unsubscribe dialog shown and cancelled on the real account.
