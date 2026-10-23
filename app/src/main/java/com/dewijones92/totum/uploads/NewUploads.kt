package com.dewijones92.totum.uploads

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.queue.GroupSpot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class WatchedGroup(val groupId: String, val shuffle: Pin.ShuffleAllOf, val seen: Set<String>)

@Serializable
data class UploadsState(
    val groups: Map<String, WatchedGroup> = emptyMap(),
    val inboxSeen: Map<String, List<String>> = emptyMap(),
)

interface UploadsStore {
    suspend fun load(): UploadsState
    suspend fun save(state: UploadsState)
}

data class InboxSources(val sources: List<MediaSource>, val complete: Boolean)

interface UploadsQueue {
    suspend fun ready() {}
    fun groupIds(): Set<String>
    fun add(group: QueueGroup, items: List<PlayableItem>, spot: GroupSpot): Int
}

data class UploadsReport(
    val addedToGroups: Map<String, Int>,
    val addedToInbox: Int,
    val failed: List<String>,
    val unwatched: List<String> = emptyList(),
) {
    val added: Int get() = addedToGroups.values.sum() + addedToInbox
}

class NewUploads(
    private val latest: suspend (MediaSource) -> List<MediaItem>?,
    private val queue: UploadsQueue,
    private val inboxSources: suspend () -> InboxSources,
    private val store: UploadsStore,
    private val adopt: suspend (groupId: String) -> WatchedGroup? = { null },
) {
    private val lock = Mutex()

    suspend fun remember(groupId: String, shuffle: Pin.ShuffleAllOf, seen: Collection<MediaItem>) = lock.withLock {
        val state = store.load()
        val watched = WatchedGroup(groupId, shuffle, seen.mapTo(mutableSetOf()) { it.id.value })
        store.save(state.copy(groups = state.groups + (groupId to watched)))
        Diag.log(
            TAG,
            "watching \"${shuffle.title}\" ($groupId) for new uploads: " +
                "${watched.seen.size} known, ${shuffle.sources.size} source(s)",
        )
    }

    suspend fun check(why: String, onlyGroup: String? = null): UploadsReport {
        queue.ready()
        val eligible = if (onlyGroup == null) inboxSources() else InboxSources(emptyList(), complete = false)
        if (!eligible.complete && onlyGroup == null) {
            Diag.log(TAG, "inbox: the source list is incomplete this time; what it remembers is kept")
        }
        val plan = lock.withLock { plan(onlyGroup, eligible) }
        val sources = (plan.groups.flatMap { g -> g.shuffle.sources.mapNotNull { it.toSource() } } + plan.inbox)
            .distinctBy { it.id }
        val fetched = fetchAll(sources)
        val failed = sources.filter { fetched[it.id.value] == null }.map { it.title } + plan.failed
        return lock.withLock { apply(why, plan, fetched, failed) }
    }

    private class Plan(
        val groups: List<WatchedGroup>,
        val inbox: List<MediaSource>,
        val unwatched: List<String>,
        val eligible: Set<String>,
        val failed: List<String>,
    )

    private suspend fun plan(onlyGroup: String?, eligible: InboxSources): Plan {
        val state = store.load()
        val inQueue = queue.groupIds()
        state.groups.values.filter { it.groupId !in inQueue }.forEach {
            Diag.log(TAG, "\"${it.shuffle.title}\" left the queue; no longer watched")
        }
        val adopted = inQueue.filter { it.startsWith(SHUFFLE_PREFIX) && it !in state.groups }.mapNotNull { id ->
            adopt(id).also { found ->
                Diag.log(
                    TAG,
                    if (found == null) {
                        "$id is in the queue but nothing says what it shuffled; not watched"
                    } else {
                        "$id adopted from Recently: ${found.seen.size} queued or played item(s) count as seen"
                    },
                )
            }
        }
        val watched = state.groups.values.filter { it.groupId in inQueue } + adopted
        val groups = watched.filter { onlyGroup == null || it.groupId == onlyGroup }
        val unwatched = listOfNotNull(onlyGroup?.takeIf { id -> watched.none { it.groupId == id } })
        val covered = watched.flatMap { g -> g.shuffle.sources.map { it.id } }.toSet()
        val unreadable = groups.flatMap { g -> g.shuffle.sources.filter { it.toSource() == null }.map { it.title } }
        unreadable.forEach { Diag.warn(TAG, "a shuffled source \"$it\" has no usable link; not checked") }
        val inbox = if (onlyGroup != null) {
            emptyList()
        } else {
            eligible.sources.filter { source ->
                (source.id.value !in covered).also { free ->
                    if (!free) {
                        Diag.log(
                            TAG,
                            "inbox: \"${source.title}\" has a shuffled group in the queue; its uploads go there"
                        )
                    }
                }
            }
        }
        val known = eligible.sources.mapTo(mutableSetOf()) { it.id.value }
        val keep = if (eligible.complete) known else known + state.inboxSeen.keys
        return Plan(groups, inbox, unwatched, keep, unreadable)
    }

    private suspend fun fetchAll(sources: List<MediaSource>): Map<String, List<MediaItem>?> =
        withContext(PoliteGate.background()) { fetchEach(sources) }

    private suspend fun fetchEach(sources: List<MediaSource>): Map<String, List<MediaItem>?> = coroutineScope {
        val permits = Semaphore(IN_FLIGHT)
        sources.map { source -> async { source.id.value to permits.withPermit { latest(source) } } }.awaitAll().toMap()
    }

    private suspend fun apply(
        why: String,
        plan: Plan,
        fetched: Map<String, List<MediaItem>?>,
        failed: List<String>,
    ): UploadsReport {
        val state = store.load()
        val inboxSeen = state.inboxSeen.filterKeys { it in plan.eligible }.toMutableMap()
        val addedToGroups = mutableMapOf<String, Int>()
        val groups = state.groups.toMutableMap()
        val stillQueued = queue.groupIds()
        plan.groups.forEach { planned ->
            if (planned.groupId !in stillQueued) {
                Diag.log(TAG, "\"${planned.shuffle.title}\" left the queue during the check; nothing added to it")
                return@forEach
            }
            val group = groups[planned.groupId] ?: planned
            val (updated, added) = refreshGroup(group, fetched)
            groups[group.groupId] = updated
            addedToGroups[group.groupId] = added
            group.shuffle.sources.filter { it.id in plan.eligible }
                .forEach { source -> fetched[source.id]?.let { noteSeen(inboxSeen, source.id, it) } }
        }
        val fresh = plan.inbox.flatMap { source -> inboxNews(source, fetched[source.id.value], inboxSeen) }
        val playable = fresh.sortedByDescending { it.publishedAt }.mapNotNull { it.toPlayableOrNull() }
        val inboxAdded = if (playable.isEmpty()) 0 else queue.add(INBOX, playable, GroupSpot.END)
        if (plan.inbox.isNotEmpty() || inboxAdded > 0) {
            Diag.log(
                TAG,
                "inbox: ${plan.inbox.size} source(s) checked, ${fresh.size} new, " +
                    "$inboxAdded added to \"${INBOX.title}\""
            )
        }
        val inQueue = queue.groupIds()
        store.save(UploadsState(groups.filterKeys { it in inQueue }, inboxSeen))
        return UploadsReport(addedToGroups, inboxAdded, failed, plan.unwatched).also {
            val line = "check ($why): ${it.added} added (groups $addedToGroups, inbox $inboxAdded), " +
                "${failed.size} failed $failed" +
                if (plan.unwatched.isEmpty()) "" else ", not watched ${plan.unwatched}"
            Diag.log(TAG, line)
            Vitals.set("uploads.lastCheck", "${java.time.Instant.now()} $line")
            Vitals.set(
                "uploads.watchedGroups",
                groups.values.joinToString(" | ") { g -> "${g.shuffle.title} (${g.seen.size} seen)" }
            )
        }
    }

    private fun refreshGroup(group: WatchedGroup, fetched: Map<String, List<MediaItem>?>): Pair<WatchedGroup, Int> {
        val shuffle = group.shuffle
        val items = shuffle.sources.flatMap { fetched[it.id].orEmpty() }.filter { shuffle.includeShorts || !it.isShort }
        val fresh = items.filter { it.id.value !in group.seen && !it.membersOnly }.sortedByDescending { it.publishedAt }
        val playable = fresh.mapNotNull { it.toPlayableOrNull() }
        val added = if (playable.isEmpty()) {
            0
        } else {
            queue.add(
                QueueGroup(group.groupId, shuffle.title),
                playable,
                GroupSpot.NEXT
            )
        }
        Diag.log(
            TAG,
            "\"${shuffle.title}\": ${items.size} latest, ${fresh.size} new " +
                "(${fresh.joinToString { it.title.take(TITLE_CHARS) }}), $added added next in the group",
        )
        return group.copy(seen = group.seen + items.map { it.id.value }) to added
    }

    private fun inboxNews(
        source: MediaSource,
        got: List<MediaItem>?,
        seen: MutableMap<String, List<String>>,
    ): List<MediaItem> {
        if (got == null) return emptyList()
        val items = got.filter { !it.isShort && !it.membersOnly }
        val before = seen[source.id.value]
        noteSeen(seen, source.id.value, got)
        if (before == null) {
            Diag.log(
                TAG,
                "inbox: first look at \"${source.title}\", ${items.size} existing upload(s) noted, none added"
            )
            return emptyList()
        }
        return items.filter { it.id.value !in before }
    }

    private fun noteSeen(seen: MutableMap<String, List<String>>, sourceId: String, items: List<MediaItem>) {
        val current = items.map { it.id.value }.distinct()
        val inFeed = current.toSet()
        val older = seen[sourceId].orEmpty().filter { it !in inFeed }
        seen[sourceId] = current + older.take(OLDER_KEPT)
    }

    companion object {
        val INBOX = QueueGroup("inbox:new", "📥 New")
        private const val TAG = "uploads"
        private const val SHUFFLE_PREFIX = "shuffle:"
        private const val TITLE_CHARS = 40
        private const val OLDER_KEPT = 200
        private const val IN_FLIGHT = 4
    }
}

private val MediaItem.isShort: Boolean
    get() = contentKind == MediaContentKind.SHORT || mediaUrl?.value?.contains("/shorts/") == true
