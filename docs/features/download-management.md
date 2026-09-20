---
title: Managing downloads — cancel, retry, sort, and see what failed
kind: feature
status: shipped
area: downloads
updated: 2026-09-20
---

# Managing downloads

Dewi, 2026-08-07: *"improve the download manager experience ... e.g. cancel inprogress download, sort
by size, sort by other stuff etc etc etc etc etc comprehensve please"*.

Three of these were things that could not be done or seen **at all**.

## Cancel

There was no cancel, and the reason was structural: nothing held the coroutine doing the fetching.
`scope.launch { … }` and the job was gone. Once a download started it ran to completion whatever you
did — on a phone, minutes and hundreds of megabytes for a video started by accident.

`DefaultDownloadManager` now holds the job per item behind a mutex, and `cancel(id)`:

- stops the coroutine (`cancelAndJoin`, so the strategy's flow really unwinds rather than being
  forgotten about);
- deletes the **partial file**, which otherwise becomes invisible bytes — no record points at it, so
  nothing in the app could show it, play it or delete it;
- forgets the record and emits so notifications settle.

Cancelling something already finished is a no-op, not an error: by the time a tap arrives the
download may have completed, and that race must not throw at the person who tapped.

### The latent bug it uncovered

`delete()` on a download **in flight** removed the record while the coroutine carried on and wrote
its next progress update straight back — so the row reappeared seconds later and the bytes kept
arriving. A deletion that undid itself. Nobody had reported it because there was no way to watch a
download closely enough to notice. `delete` now stops the job first.

## Retry

A failed download had nowhere to be seen and nothing to do about it. `retry(id)` starts it over —
and fetches **the variant originally asked for**, which needed the request persisting: a finished
download states its own `audioOnly` but a failed row did not, so a retry of one of the queue's
audio-only downloads would quietly have fetched the whole video, spending several times the data on
a connection the person had already been careful about.

## Seeing what failed

The Library listed finished downloads and in-flight ones. A failure simply vanished from the UI while
its row sat in the database, so an episode that never arrived came with no explanation. There is now
a section for them, showing **the reason verbatim** — "members only", "no space" and "the home server
was not there" are the three things worth telling apart, and a generic "download failed" hides all
three. Each offers *Try again* or *Delete*.

## Sorting, including by size

`MediaSort` orders by what a `MediaItem` knows, and an item does not know how many bytes its file
takes — only a download does. So `DownloadSort` **wraps** the shared sort rather than restating it:
every item-based order is the same comparator every other list uses, and only the two about the file
are new. `DownloadSort.ALL` is derived from `MediaSort.entries`, so adding an option there appears
here with nothing kept in step by hand.

`SectionHeaderWithSortOptions` and `SortControl` became generic over their option type, so the menu
looks and behaves identically wherever it appears.

**Sizing happens before ordering.** The one way this can be wired wrongly and still compile: sorting
a list that does not know its sizes yet silently produces source order.

### A real bug the tests caught first

`DownloadSort.ALL` was a stored `val` in the companion object. The companion of a sealed interface
initialises **before** the nested objects it declares, so the list captured `Largest` while it was
still null — the menu really did contain `[…, null, Smallest]`. It is a `get()` now. That would have
shipped as a null entry in the sort menu.

## One stream behind all of it

The in-progress row used to print the raw media id — a downloading video appeared as `chxbS3N3Llc` —
because `observeDownloads()` gives states with no items and `observeDownloaded()` gives items only
for **finished** downloads. So anything running or failed could be counted but not named.

`observeRecords()` carries the item with its state for every row, and the Library's three sections
are all derived from it: they cannot disagree with each other, and a row that has not finished can
still show a title.

## Coverage

| Level | What it holds |
|---|---|
| JVM unit | `CancelAndRetryTest` (8) — cancel stops the strategy's flow, forgets the record and deletes the partial file; cancelling nothing is a no-op; **delete-in-flight stops it** rather than letting it write itself back; retry restarts and keeps `audioOnly`; retrying an unknown id does nothing |
| JVM unit | `DownloadSortTest` (7) — largest/smallest; every item order proven identical to `MediaSort`'s; the menu derived rather than listed; stable for equal sizes |
| JVM unit | `DownloadManagementTest` (8) — in-flight rows named not id'd, cancel and cancel-all reaching the manager, cancel-all taking a snapshot, failures listed with reasons, retry and dismiss |
| Instrumented | `DownloadRowStatesTest` (7) — the row shows a title, the cancel button works, cancel-all appears only with more than one running, a failure shows its reason with working *Try again* and *Delete* |

## Not done

- **Pause and resume.** Cancel throws the partial file away; a pause would have to keep it and the
  strategies have no notion of resuming a byte range. Worth doing, bigger than this.
- **Reordering the download queue.** Downloads start when asked and there is no queue of pending ones
  to order — the auto-downloader starts them as the play queue advances.

## Every row's download control is live (2026-09-06)

`onDownload`/`onDeleteDownload` were the only two `MediaItemRow` callbacks without an app-wide default,
so Related, Notifications and Search passed `{}` and drew a download icon that did nothing. They now
default to `LocalItemActions` like every other action (full media, as a screen's own Download tap
fetches), and a row with nothing behind them draws no control rather than an inert one. Previews keep
their `{}` and simply show no icon. `MediaItemRowKeepsActionsTest` drives the default.

## Deleting one download (2026-09-20)

Dewi: *"in the 'download' section in the app it has said I have used lots of space on my phone …
give option to delete file (the offline file) on each item please"*.

The capability was already there — and had been since the beginning. The problem was that the only
way to reach it was the trailing control, which for a downloaded item is a **green filled tick**.
Everything about that glyph says *status*: this is held offline. Nothing about it says *tap me to
free 300MB*, and its content description (`Downloaded — tap to remove`) is read by TalkBack and
nobody else. A destructive action that can only be found by guessing has not been offered.

So **"Delete downloaded file" is now a named entry in the action sheet**, added once in
`ActionSheet` and therefore present on every surface that shows one — rows, the full player, the
shorts reel — shown only when there is actually a copy on disk. That also gives it to the **queue**,
whose trailing slot is the drag handle, so its rows previously had no delete at all.

Deliberately **no confirmation**. Clearing space means working down a list, a confirm per item is
exactly the friction that stops that, and a download is re-fetchable. The **Library** row carries
each copy's size, which is what makes that list worth working down — and the Library is where
clearing space actually happens. Worth knowing that the other surfaces do not show a size, so the
argument is strongest exactly where it is used.

### The sheet had already outgrown a short screen

Adding a thirteenth action is what exposed it, but the fault was older: `ActionSheet` opened
**partially expanded** and its content could not scroll, so anything past the fold was unreachable
with nothing to suggest it existed. At 1080x2400 "Go to channel" was already sitting on the very
bottom edge — visible in the screenshot taken to check this change, and not registered at the time.

CI caught it on a smaller emulator than this laptop's, and it reproduced here at **1080x1920 @
480dpi** (`adb shell wm size` / `wm density`), which is how the fix was checked rather than
guessed. The partially-expanded state was the half that mattered: it offsets the whole sheet rather
than constraining its content, so an inner scroll had nothing to scroll against. The sheet now
opens fully expanded AND scrolls, verified down to a 426dp-tall screen.
