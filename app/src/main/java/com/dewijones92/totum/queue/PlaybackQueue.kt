package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.QueueSnapshot.Companion.NOTHING_PLAYING
import com.dewijones92.totum.data.queue.QueueStore
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayRoute
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.Refusal
import com.dewijones92.totum.domain.routeNow
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The app's single queue, unified across both pillars, and the spine of playback:
 * tapping anything anywhere lands here.
 *
 * The playing item is a **member** of the queue, addressed by a cursor, not something
 * living outside it. That is what makes jumping and advancing non-destructive —
 * moving the cursor leaves everything before it in place, so you can go back — and it
 * is what makes [peek] meaningful: playing something that never joins the queue.
 *
 * Entries are [PlayableItem]s, the same shape local playlists and play history store,
 * so "Play all" and "replay from history" need no conversion. Each carries an optional
 * [QueueGroup] tag naming the run it arrived in; the list itself stays flat, so
 * grouping costs playback nothing.
 *
 * Videos resolve just-in-time when they become current, so a queue of videos never
 * pre-extracts URLs that would expire. The whole thing is persisted through
 * [QueueStore] — cursor included — so it survives a restart.
 */
// The queue's whole command surface (add/insert/remove/reorder/jump/advance), each a
// small operation over one list plus a cursor. Splitting it would scatter the single
// owner of queue order, which is the point of the class.
@Suppress("TooManyFunctions")
class PlaybackQueue(
    private val controller: PlaybackController,
    private val launcher: VideoPlaybackLauncher,
    private val scope: CoroutineScope,
    private val store: QueueStore = InMemoryQueueStore(),
    /**
     * Called when the user deliberately queues a single item, so the choice can be mirrored
     * somewhere else — today, to YouTube's Watch Later, which is how queueing something here
     * becomes a preference signal on the account.
     *
     * A hook rather than a YouTube dependency: the queue has no business knowing an account
     * exists, and the mirror is wired in AppContainer where every other integration lives.
     *
     * Deliberately NOT called by playAll or playNow. playAll is a bulk run — the shorts reel
     * hands over fifty items at a time and would bury Watch Later — and playNow means "I am
     * watching this now", which the watch-history sync already reports. Watch Later is for
     * intent, so only the two add-paths that express intent fire it.
     */
    private val onQueuedByUser: suspend (PlayableItem) -> Unit = {},
    /** Injected so the repeat guard is testable without waiting in real time. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The downloaded copy of an item, if there is one — asked at play time, for every pillar.
     *
     * The queue cannot rely on handles for this: a handle is fixed when the item is queued, and the
     * auto-downloader finishes long afterwards. Consulting the store instead means "play the local
     * copy if we have one" is true for everything in the queue, which is what offline playback
     * actually requires.
     *
     * Carries the variant, not just the path: the automatic downloads are audio-only, and whether a
     * copy holds a picture decides whether it can stand in for streaming (see [routeNow]).
     */
    private val localCopy: suspend (MediaItemId) -> LocalCopy? = { null },
    /**
     * Whether there is no usable network right now.
     *
     * Consulted so an item that can only come over the wire is declined immediately instead of
     * being attempted. Without it, playing a non-downloaded item on a plane spends the full stall
     * budget — a stall, two rescues, a give-up, about a minute — to reach a conclusion the device
     * already knew at the start, and every second of that is a spinner.
     */
    private val offline: () -> Boolean = { false },
    /**
     * Asks an item's source to get it ready again — the second thing to try when a stream dies.
     *
     * Pillar-routed by the caller (see `AppContainer`), and the SAME routing the prefetcher uses,
     * so "get this ready" means one thing in the app rather than two that could drift apart.
     */
    private val refresh: suspend (PlayableItem) -> Unit = {},
    /**
     * Whether playback should be audio-only right now — the resolved Listen mode.
     *
     * Consulted for a torrent, which is one file carrying both tracks: the home server offers a
     * remuxed audio-only version at 2.1 MB/min against the video's 15.2, and this is what
     * decides to ask for it. Default false so tests and previews behave as before.
     */
    private val audioPreferred: () -> Boolean = { false },
) {
    private val _state = MutableStateFlow(QueueSnapshot())

    /** The queue and where playback is within it. */
    val state: StateFlow<QueueSnapshot> = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<PlayableItem?>(null)

    /**
     * The item most recently handed to the player, whether or not it is a queue member.
     *
     * Distinct from `state.value.current` on purpose. The cursor answers "where are we in the
     * queue", which is -1 for a peek and for anything played before hydration lands — and the
     * player was using the cursor to decide whether to offer its item actions, so add-to-queue
     * and friends silently disappeared for exactly those items. "What is playing" and "where
     * is the cursor" are different questions and now have different answers.
     */
    val nowPlaying: StateFlow<PlayableItem?> = _nowPlaying.asStateFlow()

    /**
     * The item the RUNGS act on — what is playing, falling back to the cursor.
     *
     * Every recovery rung used to read `_state.value.current`, which is the CURSOR, and the cursor is
     * -1 for a peek by design (see [nowPlaying], and `peek`'s own "cursor cleared by design"). So all
     * four rungs answered "nothing is playing" for any peeked item and the entire ladder was dead for a
     * first-class action — long-press → Peek, on Videos, Search and Podcasts rows. The pillar guard
     * added on 2026-08-18 asked the cursor too, closing the last rung that still happened to work.
     *
     * This is the third time this queue has confused the two questions; the other two are recorded on
     * [nowPlaying] and on `advanceFrom`. Hence one named accessor rather than four call sites.
     */
    private val playingNow: PlayableItem?
        get() = _nowPlaying.value ?: _state.value.current?.item

    private val _freshStarts = MutableSharedFlow<MediaItemId>(extraBufferCapacity = FRESH_START_BUFFER)

    /**
     * Every play that was somebody's *intent* — a tap, an auto-advance, a peek — as opposed to
     * recovery replaying what is already current after a stream died.
     *
     * The distinction exists for one consumer, `StreamRecovery`, and one bug. Its retry budget is
     * per stuck point, and with no way to hear about a new start it kept counting: in report
     * 0.1.383 a video was correctly skipped after three failed recoveries, and the two hand-taps
     * that followed were then refused instantly — same item, same position, budget already spent
     * — so the app jumped to the next video without trying once. Emitted from [play], the single
     * place playback starts, so nothing can begin without saying so.
     */
    val freshStarts: SharedFlow<MediaItemId> = _freshStarts.asSharedFlow()

    /**
     * Whether anything has changed the queue yet. Loading is suspending, so the user
     * can act before it lands — this makes their intent win instead of being
     * silently replaced by the restored queue.
     */
    private var touched = false

    init {
        scope.launch {
            val saved = store.load().deduplicated()
            Diag.log(
                "queue",
                "hydrated ${saved.entries.size} entries, cursor ${saved.currentIndex}" +
                    if (touched) " — discarded, the user got there first" else "",
            )
            if (!touched) _state.value = saved
        }
        // Persist every subsequent change. `drop(1)` skips the initial empty value
        // so an empty start can't wipe a saved queue before hydration lands.
        _state.drop(1).onEach { store.save(it) }.launchIn(scope)
    }

    /** Adds to the end of the queue, moving it there if it is already queued. */
    fun enqueue(item: PlayableItem, group: QueueGroup? = null) {
        mutate("add-to-end") { snapshot ->
            snapshot.relocating(item) { without ->
                without.copy(entries = without.entries + QueueEntry(item, group))
            }
        }
        mirror(item)
    }

    /** Inserts so it plays immediately after the current entry, moving it if already queued. */
    fun playNext(item: PlayableItem, group: QueueGroup? = null) {
        mutate("play-next") { snapshot ->
            snapshot.relocating(item) { without ->
                without.inserted(listOf(QueueEntry(item, group)))
            }
        }
        mirror(item)
    }

    /**
     * Fires the mirror without letting it affect queueing.
     *
     * Its own coroutine and its own try/catch: the queue must change instantly and locally
     * whatever the network does, so a slow or failed Watch Later write can never delay a tap or
     * lose the queue entry that the user actually asked for.
     */
    private fun mirror(item: PlayableItem) {
        scope.launch {
            runCatching { onQueuedByUser(item) }
                .onFailure { Diag.warn("queue", "could not mirror \"${item.item.title}\" to the account", it) }
        }
    }

    /**
     * The app's normal "tap to play": puts [item] in the queue at the current
     * position and plays it, so pressing something never discards what was lined up.
     * An item already queued is moved rather than duplicated.
     */
    suspend fun playNow(item: PlayableItem, group: QueueGroup? = null): Boolean {
        // A repeat of the SAME item within a blink is never a real second request. A report
        // from Dewi's phone showed play-now firing seventeen times in twelve seconds, about
        // every 170ms, alternating between two videos — and since each one resolves, and a
        // resolve now costs 10-20s with the JS runtime, that is minutes of duplicated work
        // for a single tap. No human taps the same row twice in a sixth of a second, so
        // collapsing them changes nothing a user asked for.
        //
        // A guard rather than a fix for the caller: whatever is re-firing is still worth
        // finding (the log below names it), but the queue should not be re-entrant on a
        // repeat regardless of who calls it.
        val now = clock()
        val sinceLast = now - lastPlayNowAt
        val repeat = item.item.id == lastPlayNowId && sinceLast < REPEAT_WINDOW_MS
        lastPlayNowId = item.item.id
        lastPlayNowAt = now
        if (repeat) {
            Diag.warn("queue", "play-now REPEAT ${sinceLast}ms apart — ignored: ${item.item.title.take(TITLE_CHARS)}")
            return true
        }

        var index = NOTHING_PLAYING
        mutate("play-now") { snapshot ->
            val withoutIt = snapshot.removing { it.item.item.id == item.item.id }
            withoutIt.inserted(listOf(QueueEntry(item, group))).also { index = it.currentIndex + 1 }
        }
        return playAt(index)
    }

    private var lastPlayNowId: MediaItemId? = null
    private var lastPlayNowAt = 0L

    /**
     * Plays [items] as a run inserted after the current entry, starting with the
     * first. Deliberately does not replace the queue — an unwanted run is one
     * "remove these" away, whereas a replaced queue is gone. No-op if empty.
     */
    fun playAll(items: List<PlayableItem>, group: QueueGroup? = null) {
        if (items.isEmpty()) return
        // Distinct within the run as well as against the queue: a caller can legitimately hand
        // over a list with repeats (a feed showing the same video twice), and re-opening the
        // shorts reel hands over the whole run again every time.
        val run = items.distinctBy { it.item.id }
        val ids = run.map { it.item.id }.toSet()
        var index = NOTHING_PLAYING
        mutate("play-all(${run.size})") { snapshot ->
            // The playing item is refreshed in place and NOT re-inserted. It is exempt from the
            // remove-and-re-add every other entry goes through, so adding it again left the queue
            // holding two copies and then moved the cursor onto the newly-added one — which is how
            // the refresh kept landing on the entry being abandoned.
            val refreshed = snapshot.adoptingRoutesFrom(run)
            val playing = refreshed.current?.item?.item?.id
            refreshed
                .removing { entry -> entry.item.item.id in ids && entry.item.item.id != playing }
                .inserted(run.filterNot { it.item.id == playing }.map { QueueEntry(it, group) })
                // Where the run's first item ACTUALLY ended up, rather than assuming it was
                // inserted after the cursor: it may be the entry the cursor is already on.
                .also { now -> index = now.entries.indexOfFirst { it.item.item.id == run.first().item.id } }
        }
        scope.launch { playAt(index) }
    }

    /**
     * Plays [item] **without it joining the queue** — "peek": a one-off listen or
     * watch that leaves the queue, and your place in it, exactly as they were. The
     * cursor is cleared, so the next advance restarts from the queue's beginning
     * rather than pretending the peeked item was a member.
     */
    suspend fun peek(item: PlayableItem): Boolean {
        mutate("peek (cursor cleared by design)") { it.copy(currentIndex = NOTHING_PLAYING) }
        return play(item)
    }

    /** Plays the entry at [index]; nothing before it is discarded. */
    fun jumpTo(index: Int) {
        scope.launch { playAt(index) }
    }

    fun removeAt(index: Int) {
        mutate("remove-at-$index") { snapshot ->
            if (index !in snapshot.entries.indices) {
                snapshot
            } else {
                snapshot.removingAt(index)
            }
        }
    }

    /** Drops every entry tagged with [groupId] — the batch action a grouped run offers. */
    fun removeGroup(groupId: String) {
        mutate("remove-group") { snapshot -> snapshot.removing { it.group?.id == groupId } }
    }

    /** Reorders one entry, carrying the cursor with the entry it points at. */
    fun move(from: Int, to: Int) {
        mutate("move $from->$to") { snapshot ->
            if (from !in snapshot.entries.indices || to !in snapshot.entries.indices) {
                snapshot
            } else {
                val current = snapshot.current
                val reordered = snapshot.entries.toMutableList().apply { add(to, removeAt(from)) }
                snapshot.copy(
                    entries = reordered,
                    currentIndex = current?.let(reordered::indexOf) ?: snapshot.currentIndex,
                )
            }
        }
    }

    fun clear() {
        mutate("clear") { QueueSnapshot() }
    }

    /**
     * Starts the entry after the one that is PLAYING, skipping any that fail to play (an expired
     * or private video, a broken item) so one bad entry cannot strand the rest.
     *
     * Advances from what is playing rather than from the stored cursor, and that distinction is
     * the whole bug this fixes. A peeked item plays with the cursor at -1 **by design**, so
     * `currentIndex + 1` was `0` — and when the peeked item was itself somewhere in the queue,
     * "advancing" replayed the very video that had just finished. A real report (0.1.199): peek at
     * 19:11, ended at 19:27, `advance=true`, and the next transition was the same id. It then
     * ended again and was refused as "already handled", leaving playback stuck until Dewi moved
     * it by hand.
     *
     * Suspending, so the returned value is the truth. It used to return `true` the moment an index
     * existed, before the coroutine had tried anything — which is why the trail said `advance=true`
     * while nothing had actually moved on, and why the report was harder to read than it should
     * have been.
     */
    suspend fun playNextInQueue(): Boolean {
        val snapshot = _state.value
        val playingId = _nowPlaying.value?.item?.id
        val from = advanceFrom(snapshot, playingId)
        var index = from + 1
        if (index > snapshot.entries.lastIndex) {
            Diag.log("queue", "nothing after ${playingId?.value ?: "cursor $from"} of ${snapshot.entries.size}")
            return false
        }
        while (index <= _state.value.entries.lastIndex) {
            val entry = _state.value.entries[index]
            val title = entry.item.item.title.take(TITLE_CHARS)
            // Never advance onto the thing already playing. Belt and braces alongside the index
            // fix above: it also covers a duplicate that predates the de-duplication work.
            if (entry.item.item.id == playingId) {
                Diag.log("queue", "skipping index $index \"$title\" — it is what just played")
            } else if (playAt(index, rollBackOnRefusal = true)) {
                // The SUCCESS says what it advanced to, not just that it did. Only the refusals
                // said anything, so a report of a queue advancing wrongly showed "advance=true"
                // and nothing about which of sixty items it had landed on.
                Diag.log(
                    "queue",
                    "advanced from index $from to $index of ${_state.value.entries.size} — \"$title\"",
                )
                return true
            } else {
                // An item the player would not start. Silent before, so "nothing playable after
                // index 3" with a full queue gave no clue WHICH items were refused.
                Diag.warn("queue", "index $index \"$title\" would not play; trying the next one")
            }
            index++
        }
        Diag.log("queue", "nothing playable after index $from")
        return false
    }

    /**
     * What playing on would start, without starting it — so the next item can be resolved
     * BEFORE it is needed.
     *
     * Shares [advanceFrom] with the advance itself rather than re-deriving "next", because two
     * definitions of the same thing is precisely the bug that made a finished video replay
     * itself: one place asked the cursor, the other asked what was playing.
     */
    fun peekNext(): PlayableItem? {
        val snapshot = _state.value
        val playingId = _nowPlaying.value?.item?.id
        return snapshot.entries
            .drop(advanceFrom(snapshot, playingId) + 1)
            .firstOrNull { it.item.item.id != playingId }
            ?.item
    }

    /**
     * The index advancing counts from: where the PLAYING item sits, falling back to the cursor
     * when what is playing is not a queue member at all.
     */
    private fun advanceFrom(snapshot: QueueSnapshot, playingId: MediaItemId?): Int =
        playingId
            ?.let { id -> snapshot.entries.indexOfFirst { it.item.item.id == id } }
            ?.takeIf { it >= 0 }
            ?: snapshot.currentIndex

    /**
     * Moves the cursor to [index] and plays it; false when out of range or unplayable.
     *
     * ROLLS BACK on refusal. The cursor moved first and nothing put it back, and since [mutate] is what
     * triggers `store.save`, a failed advance PERSISTED the move. Over a mostly-unstreamed queue the
     * advance loop walks every remaining entry, so one auto-advance offline parked the cursor on the
     * last item: `upNext` then reads empty, every later advance says "nothing after cursor N", and a
     * 97-item queue looks finished even after the network returns — across a restart, because it was
     * saved. Offline is the ordinary trigger, since `routeNow` refuses everything with no copy on disk.
     *
     * The cursor is restored rather than left alone up front, because `play` -> `route` reads
     * `_state.value.current` to decide what to play, so the move has to happen before the attempt.
     */
    private suspend fun playAt(index: Int, rollBackOnRefusal: Boolean = false): Boolean {
        val entry = _state.value.entries.getOrNull(index) ?: return false
        val cursorBefore = _state.value.currentIndex
        val playingBefore = _nowPlaying.value
        mutate("play-at-$index") { it.copy(currentIndex = index) }
        if (play(entry.item)) return true
        // ONLY for the automatic advance, which is why the caller has to ask. An explicit tap must
        // leave the item it chose as current even when it will not play, because that is what the
        // recovery ladder acts on -- rolling THAT back stops a refused item from ever being rescued,
        // and broke the SABR-rescue-offline test the moment it was applied everywhere.
        if (rollBackOnRefusal && cursorBefore != index) {
            Diag.log(
                "queue",
                "index $index would not play — putting the cursor back to $cursorBefore and " +
                    "\"${playingBefore?.item?.title?.take(TITLE_CHARS) ?: "nothing"}\" back as playing",
            )
            mutate("play-at-$index-rolled-back") { it.copy(currentIndex = cursorBefore) }
            _nowPlaying.value = playingBefore
        }
        return false
    }

    /**
     * Every change goes through here, so nothing can bypass the hydration guard.
     *
     * [why] names the operation, because the snapshot alone is ambiguous in exactly the way
     * that matters: a cursor of -1 is what both a "peek" and a hydration-with-nothing-playing
     * look like, and telling them apart decided whether an auto-advance failure was a bug or
     * by design. One word of intent per mutation makes the trail readable.
     */
    private fun mutate(why: String, block: (QueueSnapshot) -> QueueSnapshot) {
        touched = true
        _state.update(block)
        val now = _state.value
        // The title is labelled, because it is the CURRENT entry and not whatever was just
        // added — reading it as "the item this operation touched" cost a wrong diagnosis on
        // 2026-07-29 (I searched Watch Later for the playing video instead of the queued one and
        // concluded the write had failed when it had not).
        Diag.log(
            "queue",
            "$why: size=${now.entries.size} current=${now.currentIndex} " +
                "playing=${now.current?.item?.item?.title ?: "-"}",
        )
    }

    /**
     * Replays whatever is current from [positionMs] — how an expired stream is recovered.
     *
     * Goes back through [play] rather than nudging the player, because for a video that
     * routing is what re-resolves the URL: the queue holds the stable watch URL, never the
     * signed one that died.
     */
    suspend fun replayCurrent(positionMs: Long): Boolean {
        val item = playingNow ?: return false
        // Recovery is the only caller, and it exists to get a FRESH stream — so the cached
        // resolution has to go first. Without this the replay hits the resolver cache and asks
        // the same dead URL again: a real report (0.1.277) shows three "recoveries" eight
        // seconds apart, each logging "cache hit … skipped extraction", after which a perfectly
        // playable video was skipped as broken.
        forgetResolved(item.item.id)
        // And for everything that is NOT a video, ask its source to get ready again — which for a
        // torrent means telling the home server to restart the remux behind its audio stream.
        //
        // Without this the rescue was far weaker than it looked for the other pillars: forgetting
        // a cached resolution only means anything for a video, so replaying a torrent or a podcast
        // re-requested the identical address. A fresh connection sometimes helps; a source that has
        // been asked to produce the stream again helps more, and it is the only second thing there
        // is to try. Found by writing the stall tests on 2026-08-03, not by a report.
        runCatching { refresh(item) }
            .onFailure { Diag.warn("playback", "could not refresh ${item.item.id.value} before replaying", it) }
        return play(item, positionMs, retry = true)
    }

    /**
     * Drops any cached resolution for [itemId], so the next play of it must resolve afresh.
     *
     * Called by recovery the moment a stream fails, not just when it replays. A signed URL that
     * has 403'd is dead for everyone, and leaving it in the cache is what made the hand-taps in
     * report 0.1.383 hopeless: `cache hit for ytZiDr1NLQc (play), skipped extraction` handed back
     * the address that had failed four times seconds earlier.
     *
     * Only a video has a resolution to forget; anything else re-requests its source through
     * [refresh], which belongs to an actual replay rather than to every failure — restarting a
     * torrent's remux on the home server is not free.
     */
    fun forgetResolved(itemId: MediaItemId) {
        val handle = entryFor(itemId)?.handle as? PlayHandle.Video ?: return
        launcher.forgetResolved(handle.watchUrl)
    }

    /** The queued (or peeked) item with this id, if the queue still knows about it. */
    private fun entryFor(itemId: MediaItemId): PlayableItem? =
        _nowPlaying.value?.takeIf { it.item.id == itemId }
            ?: _state.value.entries.firstOrNull { it.item.item.id == itemId }?.item

    /**
     * Plays [queued]; returns whether it actually started.
     *
     * [retry] marks recovery replaying what is already current, which is the one start that is
     * NOT a new intent — everything else announces itself on [freshStarts] so recovery's retry
     * budget starts over. Getting that backwards would make recovery reset its own budget on
     * every attempt and retry a dead item forever.
     */
    private suspend fun play(
        queued: PlayableItem,
        startPositionMs: Long = 0,
        retry: Boolean = false,
        streamRefused: Boolean = false,
        forceAudio: Boolean = false,
    ): Boolean {
        // Recorded before routing, so a peek and a queued play are equally "playing".
        _nowPlaying.value = queued
        if (!retry) _freshStarts.tryEmit(queued.item.id)
        return route(queued, startPositionMs, streamRefused, forceAudio)
    }

    /**
     * Plays whatever is current WITHOUT its stream — the last thing to try before giving up on an
     * item and moving on.
     *
     * Recovery calls this once its retries are spent. Everything else about routing is unchanged;
     * the single difference is that an audio-only copy of a video is now allowed to stand in,
     * because the alternative is no longer "watch it properly" but "do not play it at all".
     * See [routeNow]'s `streamRefused`.
     *
     * Returns false when there is nothing on the disk either, which is when moving on is right.
     */
    /**
     * Keeps the current item playing as SOUND when its picture cannot be served.
     *
     * The last rung before an item is abandoned. Measured 2026-08-18: a 97-minute video offered 19
     * video formats and not one carried a solved `n`, while 73 of its 77 audio formats did — so
     * watching it past the first megabyte is not a choice the app can make, and the alternative to
     * sound is silence.
     */
    suspend fun playCurrentWithoutThePicture(positionMs: Long): Boolean {
        val item = playingNow ?: return false
        // The PILLAR, like both neighbours in this ladder already check. Without it the rung asked the
        // launcher for a fallback while the launcher still held the last VIDEO it resolved -- a podcast
        // never goes through the launcher at all -- so a failing episode was "rescued" with a
        // completely different item's soundtrack, at the episode's position, and `attempts` reset so it
        // was never abandoned. Found by a podcast audit, 2026-08-18.
        // Whether the ITEM has a soundtrack to fall back to -- not which pillar it belongs to. A
        // TORRENT is a PlayHandle.Podcast and DOES have a picture, and carries a purpose-built
        // audio-only stream (15.2 MB/min against 2.1). Guarding on the pillar refused the one
        // podcast-pillar item this rung could actually rescue, and logged "a PODCAST item has no
        // picture to lose" while its audioUrl sat right there -- two situations, one false line.
        val handle = item.handle
        if (handle is PlayHandle.Podcast) {
            val audioOnly = handle.audioUrl
            if (audioOnly == null) {
                Diag.log(
                    "playback",
                    "no sound-only rescue for ${item.item.id.value}: it is already audio, so there is " +
                        "no picture to drop",
                )
                return false
            }
            Diag.log("playback", "keeping the sound for ${item.item.id.value} from its audio-only stream")
            // streamRefused stays FALSE on purpose. `routeNow` refuses outright when it is set --
            // rightly, since asking again for the stream that just died would loop -- but the
            // audio-only URL is a DIFFERENT address, which is the entire point of this rung. The
            // `rescues` cap is what bounds it if that one fails too.
            return play(item, positionMs, retry = true, forceAudio = true)
        }
        if (handle !is PlayHandle.Video) {
            Diag.log(
                "playback",
                "no sound-only rescue for ${item.item.id.value}: a ${handle.pillar} item played from " +
                    "${handle::class.simpleName} has no separate soundtrack to fall back to",
            )
            return false
        }
        val kept = launcher.listenIfPossible(item.item.id, positionMs)
        Diag.log(
            "playback",
            if (kept) {
                "keeping the sound without the picture from ${positionMs}ms — " +
                    "the video stream will not serve"
            } else {
                "no audio-only stream to fall back to; the picture was all there was"
            },
        )
        return kept
    }

    /**
     * Plays the current item over SABR, keeping the picture the ordinary streams refused.
     *
     * Video only: SABR is a YouTube protocol, so a podcast has nothing to rescue this way and says so
     * rather than failing quietly. Returns false whenever there is no SABR answer, which is the ladder's
     * cue to keep walking down to the sound.
     */
    suspend fun playCurrentOverSabr(positionMs: Long): Boolean {
        val item = playingNow ?: return false
        // Offline FIRST, and cheaply. SABR is a network route, so with no network it cannot succeed —
        // and this rung sits in the give-up ladder, which offline IS the path to "step over this and
        // play the next one". A doomed /player request in the middle of that delays the skip: adding
        // the rung turned OfflineQueuePlaybackTest red with "still on never-downloaded after 20000ms".
        if (offline()) {
            Diag.log("playback", "no SABR rescue for ${item.item.id.value}: offline, so it cannot serve")
            return false
        }
        val handle = item.handle
        if (handle !is PlayHandle.Video) {
            Diag.log("playback", "no SABR rescue for a ${handle.pillar} item — SABR is a YouTube protocol")
            return false
        }
        val rescued = launcher.playAsRescue(item.item, handle.watchUrl, positionMs)
        Diag.log(
            "playback",
            if (rescued) {
                "rescued ${item.item.id.value} over SABR from ${positionMs}ms — " +
                    "the picture is capped at 1080p30 but present"
            } else {
                "SABR could not rescue ${item.item.id.value} either"
            },
        )
        return rescued
    }

    suspend fun playCurrentWithoutItsStream(positionMs: Long): Boolean {
        val item = playingNow ?: return false
        return play(item, positionMs, retry = true, streamRefused = true)
    }

    /**
     * Picks a route with [routeNow] and carries it out — the only place playback starts.
     *
     * Both pillars go through the one decision. They used to have one branch each, and the
     * branches disagreed: the podcast side asked the download store for a local copy and the
     * video side never did, so a downloaded YouTube item was refused in airplane mode with its
     * file on the disk (Dewi, 2026-08-06). Whatever else changes, that asymmetry cannot come
     * back without deleting this call.
     */
    /**
     * The pillar is the ITEM's, never the presentation's.
     *
     * The two audio routes used to hand the player `MediaKind.PODCAST` — meaning "play this as
     * audio", not "this is a podcast" — and everything downstream believed it. `WatchHistorySync`
     * skips anything that is not a video, so **a YouTube video played from a downloaded file was
     * invisible to Dewi's own account**, which with auto-download-audio on is most of his
     * listening. Report 0.1.376 has it plainly: four plays, and only the streamed one reached
     * YouTube. The pillar badge and the mini-player icon were wrong for the same reason.
     *
     * Listen-mode STREAMING was never affected — that goes through the launcher, which lets the
     * `kind` parameter default to VIDEO. Only these two routes were wrong.
     *
     * Whether a picture is shown is `PlaybackState.hasVideo`'s business, and always was.
     */
    private suspend fun route(
        queued: PlayableItem,
        startPositionMs: Long,
        streamRefused: Boolean = false,
        /** Forces the audio-only route, for the rescue rung that IS "play this without its picture". */
        forceAudio: Boolean = false,
    ): Boolean {
        // Claimed for EVERY route, before anything is chosen. A route to a file reaches the
        // controller directly, so without claiming it here a streaming resolve still in flight
        // would land later and take playback back to the network — which is exactly what report
        // 0.1.390 did, ten seconds after the downloaded audio had started playing.
        val request = launcher.beginPlay()
        val onDisk = localCopy(queued.item.id)
        val offlineNow = offline()
        val audioNow = forceAudio || audioPreferred()
        val route = queued.routeNow(
            onDisk,
            offline = offlineNow,
            audioPreferred = audioNow,
            streamRefused = streamRefused,
        )
        // The decision AND its inputs, because a report can only ever answer the question it
        // was given the numbers for. "Skipped" with no copy and "skipped" with a copy it chose
        // not to use are the same line otherwise, and telling them apart is the whole diagnosis.
        Diag.log(
            "playback",
            "route ${queued.item.id.value} -> ${route.describe()} " +
                "[handle=${queued.handle.label} " +
                "copy=${onDisk?.let { if (it.audioOnly) "audio-only" else "full" } ?: "none"} " +
                "offline=$offlineNow listen=$audioNow streamRefused=$streamRefused]",
        )
        return when (route) {
            is PlayRoute.VideoFile -> {
                launcher.playLocal(route.playable.item, route.path)
                true
            }
            is PlayRoute.AudioFile -> {
                controller.play(
                    route.playable.item,
                    queued.handle.pillar,
                    localPath = route.path,
                    startPositionMs = startPositionMs,
                )
                true
            }
            is PlayRoute.VideoStream ->
                launcher.play(route.playable.item, route.watchUrl, startPositionMs, request)
            is PlayRoute.AudioStream -> {
                controller.play(route.playable.item, queued.handle.pillar, startPositionMs = startPositionMs)
                true
            }
            is PlayRoute.Refused -> false
        }
    }
}

/** How a route reads in the trail: what was chosen, and where the bytes come from. */
private fun PlayRoute.describe(): String = when (this) {
    is PlayRoute.VideoFile -> "the downloaded video at $path"
    is PlayRoute.AudioFile -> "the downloaded audio at $path"
    is PlayRoute.VideoStream -> "streaming the video from $watchUrl"
    is PlayRoute.AudioStream -> if (viaAudioOnlyUrl) "streaming its audio-only version" else "streaming it"
    is PlayRoute.Refused -> when (reason) {
        Refusal.NothingToPlay -> "refused: there is nothing to play"
        Refusal.NotOnThisDevice -> "refused: not downloaded and there is no network"
        Refusal.StreamWillNotPlay -> "refused: the stream will not play and there is no copy on disk"
    }
}

/**
 * Drops repeats, keeping the first of each and carrying the cursor with the entry it points at.
 *
 * Applied on load, so a queue already polluted by the duplicating add-paths repairs itself on
 * next launch rather than staying broken forever. It is also what makes the list safe to key by
 * item id alone: duplicate keys in a LazyColumn are a crash, and the old key had to include the
 * index to stay unique — which defeated Compose's item identity, so reordering lost per-item
 * state and never animated.
 */
private fun QueueSnapshot.deduplicated(): QueueSnapshot {
    val unique = entries.distinctBy { it.item.item.id }
    if (unique.size == entries.size) return this
    Diag.warn("queue", "dropped ${entries.size - unique.size} duplicate entries on load")
    return copy(entries = unique, currentIndex = current?.let(unique::indexOf) ?: NOTHING_PLAYING)
}

/**
 * Applies [place] to this snapshot with any existing copy of [item] removed, so re-adding
 * something MOVES it instead of duplicating it.
 *
 * Dewi hit this on "play next": pressing it on an item already in the queue left two copies.
 * playNow already de-duplicated and its documentation even claimed the behaviour was general,
 * so three of the four add-paths disagreed with the one that was right.
 *
 * The playing entry is deliberately exempt. Removing it would drop the cursor to -1 and the
 * queue would forget where it was, so "play next" on the thing already playing does nothing —
 * which is also the only sensible reading of the request.
 */
private fun QueueSnapshot.relocating(
    item: PlayableItem,
    place: (QueueSnapshot) -> QueueSnapshot,
): QueueSnapshot {
    if (current?.item?.item?.id == item.item.id) return adoptingRoutesFrom(listOf(item))
    return place(removing { it.item.item.id == item.item.id })
}

/**
 * Gives the playing entry any route [fresh] knows about that it does not, leaving it in place.
 *
 * The playing entry is exempt from the remove-then-re-add that every other entry goes through, so
 * it alone never picks up a better handle — it keeps whatever it was created with until it stops
 * being current. A torrent queued before its audio-only URL existed therefore went on playing as
 * video for as long as it was the thing playing, while its neighbours in the same run got the URL.
 */
private fun QueueSnapshot.adoptingRoutesFrom(fresh: List<PlayableItem>): QueueSnapshot {
    val playing = current ?: return this
    val update = fresh.firstOrNull { it.item.id == playing.item.item.id } ?: return this
    val merged = playing.item.handle.mergedWith(update.handle)
    if (merged == playing.item.handle) return this
    Diag.log("queue", "playing entry adopted a fresher route: $merged")
    return copy(
        entries = entries.map {
            if (it.item.item.id == playing.item.item.id) {
                it.copy(item = it.item.copy(handle = merged))
            } else {
                it
            }
        },
    )
}

/** Inserts [run] immediately after the current entry, leaving the cursor put. */
private fun QueueSnapshot.inserted(run: List<QueueEntry>): QueueSnapshot {
    val at = (currentIndex + 1).coerceIn(0, entries.size)
    return copy(entries = entries.take(at) + run + entries.drop(at))
}

/** Drops matching entries, keeping the cursor on whatever it pointed at. */
private fun QueueSnapshot.removing(match: (QueueEntry) -> Boolean): QueueSnapshot {
    val kept = entries.filterNot(match)
    return copy(entries = kept, currentIndex = current?.let(kept::indexOf) ?: NOTHING_PLAYING)
}

private fun QueueSnapshot.removingAt(index: Int): QueueSnapshot {
    val kept = entries.filterIndexed { i, _ -> i != index }
    // Removing the playing entry leaves nothing current; the next advance starts from
    // where it was, which is what "remove the thing I'm on" should feel like.
    val cursor = when {
        index == currentIndex -> (currentIndex - 1).coerceAtLeast(NOTHING_PLAYING)
        index < currentIndex -> currentIndex - 1
        else -> currentIndex
    }
    return copy(entries = kept, currentIndex = cursor)
}

/**
 * How close two identical play-now calls have to be to count as one. Well below a
 * deliberate double-tap, well above the ~170ms storm a real report showed, so it collapses
 * the storm without swallowing anything intended.
 */
private const val REPEAT_WINDOW_MS = 400L
private const val TITLE_CHARS = 60

/** Room for a burst of starts, so a play is never dropped because nobody has collected yet. */
private const val FRESH_START_BUFFER = 8
