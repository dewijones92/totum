package com.dewijones92.totum.uploads

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.queue.GroupSpot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

interface UploadsQueue {
    fun groupIds(): Set<String>
    fun add(group: QueueGroup, items: List<PlayableItem>, spot: GroupSpot): Int
}

data class UploadsReport(val addedToGroups: Map<String, Int>, val addedToInbox: Int, val failed: List<String>) {
    val added: Int get() = addedToGroups.values.sum() + addedToInbox
}

class NewUploads(
    private val latest: suspend (MediaSource) -> List<MediaItem>?,
    private val queue: UploadsQueue,
    private val inboxSources: suspend () -> List<MediaSource>,
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
                "${watched.seen.size} known, ${shuffle.sources.size} source(s)"
        )
    }

    suspend fun check(why: String, onlyGroup: String? = null): UploadsReport = lock.withLock {
        val state = store.load()
        val inQueue = queue.groupIds()
        val (kept, gone) = state.groups.entries.partition { it.key in inQueue }
        gone.forEach { Diag.log(TAG, "\"${it.value.shuffle.title}\" left the queue; no longer watched") }
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
        val failed = mutableListOf<String>()
        val addedToGroups = mutableMapOf<String, Int>()
        val groups = (kept.map { it.key to it.value } + adopted.map { it.groupId to it }).associate { (id, group) ->
            if (onlyGroup != null && id != onlyGroup) return@associate id to group
            val (updated, added) = refreshGroup(group, failed)
            addedToGroups[id] = added
            id to updated
        }
        val covered = groups.values.flatMap { g -> g.shuffle.sources.map { it.id } }.toSet()
        var inboxAdded = 0
        val inboxSeen = state.inboxSeen.toMutableMap()
        if (onlyGroup == null) {
            val sources = inboxSources().filter { it.id.value !in covered }
            val fresh = mutableListOf<MediaItem>()
            sources.forEach { source -> fresh += inboxNews(source, inboxSeen, failed) }
            val playable = fresh.sortedByDescending { it.publishedAt }.mapNotNull { it.toPlayableOrNull() }
            if (playable.isNotEmpty()) inboxAdded = queue.add(INBOX, playable, GroupSpot.END)
            Diag.log(
                TAG,
                "inbox: ${sources.size} source(s) checked, ${fresh.size} new, $inboxAdded added to \"${INBOX.title}\""
            )
        }
        store.save(UploadsState(groups, inboxSeen))
        UploadsReport(addedToGroups, inboxAdded, failed).also {
            val line = "check ($why): ${it.added} added (groups $addedToGroups, inbox $inboxAdded), " +
                "${failed.size} failed $failed"
            Diag.log(TAG, line)
            Vitals.set("uploads.lastCheck", "${java.time.Instant.now()} $line")
            Vitals.set(
                "uploads.watchedGroups",
                groups.values.joinToString(" | ") { g -> "${g.shuffle.title} (${g.seen.size} seen)" }
            )
        }
    }

    private suspend fun refreshGroup(group: WatchedGroup, failed: MutableList<String>): Pair<WatchedGroup, Int> {
        val shuffle = group.shuffle
        val items = shuffle.sources.flatMap { pinned ->
            val source = pinned.toSource()
            val got = source?.let { latest(it) }
            if (got == null) failed += pinned.title
            got.orEmpty()
        }.filter { shuffle.includeShorts || !it.isShortLink }
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
                "(${fresh.joinToString { it.title.take(TITLE_CHARS) }}), " +
                "$added added next in the group",
        )
        return group.copy(seen = group.seen + items.map { it.id.value }) to added
    }

    private suspend fun inboxNews(
        source: MediaSource,
        seen: MutableMap<String, List<String>>,
        failed: MutableList<String>,
    ): List<MediaItem> {
        val got = latest(source)
        if (got == null) {
            failed += source.title
            return emptyList()
        }
        val items = got.filter { !it.isShortLink && !it.membersOnly }
        val before = seen[source.id.value]
        seen[source.id.value] = (items.map { it.id.value } + before.orEmpty()).distinct().take(SEEN_PER_SOURCE)
        if (before == null) {
            Diag.log(
                TAG,
                "inbox: first look at \"${source.title}\", ${items.size} existing upload(s) noted, none added"
            )
            return emptyList()
        }
        return items.filter { it.id.value !in before }
    }

    companion object {
        val INBOX = QueueGroup("inbox:new", "📥 New")
        private const val TAG = "uploads"
        private const val SHUFFLE_PREFIX = "shuffle:"
        private const val TITLE_CHARS = 40
        private const val SEEN_PER_SOURCE = 200
    }
}

private val MediaItem.isShortLink: Boolean get() = mediaUrl?.value?.contains("/shorts/") == true
