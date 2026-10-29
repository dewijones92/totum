package com.dewijones92.totum.groups

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
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
    val fresh: List<String> = emptyList(),
    val notified: List<String> = emptyList(),
)

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
        groups.forEach { group -> check(group) }
    }

    suspend fun opened(id: SourceGroupId): Instant? = lock.withLock {
        val all = states()
        val before = all[id.value]
        val now = clock()
        put(all + (id.value to (before ?: GroupNewsState()).copy(seenAt = now.toEpochMilli(), fresh = emptyList())))
        Diag.log(
            TAG,
            "group ${id.value} opened: ${before?.fresh?.size ?: 0} new cleared, " +
                "last looked ${before?.seenAt?.let(Instant::ofEpochMilli) ?: "never"}"
        )
        before?.seenAt?.let(Instant::ofEpochMilli)
    }

    suspend fun warm() {
        val fresh = lock.withLock { states() }.values.sumOf { it.fresh.size }
        Diag.log(TAG, "group news read at start-up: $fresh new across groups")
    }

    fun newCount(id: SourceGroupId): Int = _states.value[id.value]?.fresh?.size ?: 0

    fun diagnostics(): Map<String, String> =
        _states.value.mapKeys {
            "groups.news.${it.key}"
        }.mapValues { (_, s) -> "fresh=${s.fresh.size} notified=${s.notified.size} seenAt=${s.seenAt}" }

    private suspend fun check(group: SourceGroup) {
        val seen = lock.withLock { states()[group.id.value]?.seenAt }
        if (seen == null) {
            lock.withLock { put(states() + (group.id.value to GroupNewsState(seenAt = clock().toEpochMilli()))) }
            Diag.log(TAG, "\"${group.name}\": first look; only videos after now will count as new")
            return
        }
        val since = Instant.ofEpochMilli(seen)
        val items = group.members.flatMap { member ->
            runCatching { latest(member) }.onFailure { Diag.warn(TAG, "\"${member.title}\" could not be read", it) }
                .getOrNull().orEmpty()
        }.distinctBy { it.id }
        val fresh = items.filter { it.publishedAt?.isAfter(since) == true }.sortedByDescending { it.publishedAt }
        lock.withLock {
            val state = states()[group.id.value] ?: GroupNewsState(seenAt = seen)
            val told = state.notified.toMutableList()
            fresh.filter { it.id.value !in told }.forEach { item ->
                val delivered = notify(group, item)
                Diag.log(TAG, "\"${group.name}\": new \"${item.title}\" (${item.publishedAt}) notified=$delivered")
                if (delivered) told += item.id.value
            }
            put(
                states() + (
                    group.id.value to state.copy(fresh = fresh.map { it.id.value }, notified = told.takeLast(KEEP))
                    )
            )
        }
        Diag.log(TAG, "\"${group.name}\": ${items.size} latest, ${fresh.size} new since $since")
    }

    private suspend fun states(): Map<String, GroupNewsState> {
        if (!loaded) {
            _states.value = runCatching { store.load() }.getOrElse {
                Diag.warn(TAG, "group news could not be read; starting again", it)
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
