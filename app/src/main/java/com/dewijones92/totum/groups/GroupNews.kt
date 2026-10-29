package com.dewijones92.totum.groups

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant

interface GroupNewsStore {
    suspend fun load(): Map<String, GroupNewsState>
    suspend fun save(states: Map<String, GroupNewsState>)
}

class InMemoryGroupNewsStore(private var states: Map<String, GroupNewsState> = emptyMap()) : GroupNewsStore {
    override suspend fun load() = states
    override suspend fun save(states: Map<String, GroupNewsState>) {
        this.states = states
    }
}

@Serializable
data class GroupNewsState(
    val seenAt: Long? = null,
    val freshBy: Map<String, List<String>> = emptyMap(),
    val notified: List<String> = emptyList(),
    val joined: Map<String, Long> = emptyMap(),
) {
    val fresh: List<String> get() = freshBy.values.flatten().distinct()
}

class GroupNews(
    private val store: GroupNewsStore,
    private val latest: suspend (MediaSource) -> List<MediaItem>?,
    private val notify: (SourceGroup, MediaItem) -> Boolean,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Mutex()
    private val _states = MutableStateFlow<Map<String, GroupNewsState>>(emptyMap())
    val states: StateFlow<Map<String, GroupNewsState>> = _states.asStateFlow()
    private var loaded = false

    suspend fun check(groups: List<SourceGroup>) {
        Diag.log(TAG, "checking ${groups.size} group(s) for new videos")
        forgetAllBut(groups)
        groups.forEach { group -> check(group) }
    }

    suspend fun opened(id: SourceGroupId): Instant? = lock.withLock {
        val all = states()
        val before = all[id.value]
        val now = clock()
        put(all + (id.value to (before ?: GroupNewsState()).copy(seenAt = now.toEpochMilli(), freshBy = emptyMap())))
        Diag.log(
            TAG,
            "group ${id.value} opened: ${before?.fresh?.size ?: 0} new cleared, " +
                "last looked ${before?.seenAt?.let(Instant::ofEpochMilli) ?: "never"}",
        )
        before?.seenAt?.let(Instant::ofEpochMilli)
    }

    suspend fun warm() {
        val fresh = lock.withLock { states() }.values.sumOf { it.fresh.size }
        Diag.log(TAG, "group news read at start-up: $fresh new across groups")
    }

    fun newCount(id: SourceGroupId): Int = _states.value[id.value]?.fresh?.size ?: 0

    private suspend fun forgetAllBut(groups: List<SourceGroup>) = lock.withLock {
        val keep = groups.mapTo(mutableSetOf()) { it.id.value }
        val all = states()
        val gone = all.keys - keep
        if (gone.isNotEmpty()) {
            Diag.log(TAG, "forgetting news of ${gone.size} group(s) that no longer exist: $gone")
            put(all - gone)
        }
    }

    private suspend fun check(group: SourceGroup) {
        val first = lock.withLock {
            val state = states()[group.id.value]
            if (state?.seenAt == null) {
                val now = clock().toEpochMilli()
                put(
                    states() + (
                        group.id.value to GroupNewsState(
                            seenAt = now,
                            joined = group.members.associate {
                                it.id.value to now
                            }
                        )
                        )
                )
                true
            } else {
                false
            }
        }
        if (first) return Diag.log(TAG, "\"${group.name}\": first look; only videos after now will count as new")
        val read = group.members.associate { member -> member.id.value to readOrNull(member) }
        lock.withLock { settle(group, read) }
    }

    private suspend fun readOrNull(member: MediaSource): List<MediaItem>? = try {
        latest(member)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        Diag.warn(TAG, "\"${member.title}\" could not be read; keeping what it had new", failure)
        null
    }

    private suspend fun settle(group: SourceGroup, read: Map<String, List<MediaItem>?>) {
        val state = states()[group.id.value] ?: return
        val seen = state.seenAt ?: return
        val now = clock().toEpochMilli()
        val joining = group.members.filter { it.id.value !in state.joined }
        val joined = state.joined + joining.associate { it.id.value to now }
        val freshItems = mutableListOf<MediaItem>()
        val freshBy = read.mapValues { (member, items) ->
            if (items == null) return@mapValues state.freshBy[member].orEmpty()
            val since = Instant.ofEpochMilli(maxOf(seen, joined[member] ?: seen))
            items.filter { it.publishedAt?.isAfter(since) == true }.also { freshItems += it }.map { it.id.value }
        }.filterValues { it.isNotEmpty() }
        val told = state.notified.toMutableList()
        val unannounced = freshItems.distinctBy { it.id }.filter { it.id.value !in told }
        unannounced.sortedByDescending { it.publishedAt }.forEach { item ->
            val delivered = notify(group, item)
            Diag.log(TAG, "\"${group.name}\": new \"${item.title}\" (${item.publishedAt}) notified=$delivered")
            if (delivered) told += item.id.value
        }
        val next = state.copy(freshBy = freshBy, notified = told.takeLast(KEEP), joined = joined)
        put(states() + (group.id.value to next))
        val unreadable = read.count { it.value == null }
        Diag.log(
            TAG,
            "\"${group.name}\": ${freshBy.values.sumOf { it.size }} new since ${Instant.ofEpochMilli(seen)} " +
                "($unreadable of ${read.size} member(s) unreadable, ${joining.size} joined now)",
        )
    }

    private suspend fun states(): Map<String, GroupNewsState> {
        if (!loaded) {
            _states.value = try {
                store.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                Diag.warn(TAG, "group news could not be read; starting again", failure)
                emptyMap()
            }
            loaded = true
        }
        return _states.value
    }

    private suspend fun put(states: Map<String, GroupNewsState>) {
        _states.value = states
        runCatching { store.save(states) }.onFailure { Diag.warn(TAG, "group news could not be saved", it) }
    }

    private companion object {
        const val TAG = "group"
        const val KEEP = 500
    }
}

fun GroupNews.diagnostics(): Map<String, String> = states.value.mapKeys { "groups.news.${it.key}" }
    .mapValues { (_, s) ->
        "fresh=${s.fresh.size} notified=${s.notified.size} seenAt=${s.seenAt} members=${s.joined.size}"
    }
