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
    private val muted: (SourceGroup) -> Boolean = { false },
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
        val joinedAt = if (state.joined.isEmpty()) seen else now
        val joined = state.joined + joining.associate { it.id.value to joinedAt }
        val freshBy = read.mapValues { (member, items) ->
            if (items == null) return@mapValues state.freshBy[member].orEmpty()
            val since = Instant.ofEpochMilli(maxOf(seen, joined[member] ?: seen))
            items.filter { it.publishedAt?.isAfter(since) == true }.map { it.id.value }
        }.filterValues { it.isNotEmpty() }
        val told = state.notified.toMutableList()
        val quiet = muted(group)
        val tally = AnnounceTally()
        val candidates = read.flatMap { (member, items) ->
            val floor = Instant.ofEpochMilli(joined[member] ?: seen)
            items.orEmpty().filter { tally.judge(it, floor, Instant.ofEpochMilli(now), it.id.value in told) }
        }.distinctBy { it.id }
        candidates.sortedByDescending { it.publishedAt }.forEach { item ->
            val delivered = quiet || notify(group, item)
            Diag.log(
                TAG,
                "\"${group.name}\": new \"${item.title}\" (${item.publishedAt}) " +
                    if (quiet) "not notified: this group's notifications are off" else "notified=$delivered",
            )
            tally.delivered(delivered, quiet)
            if (delivered) told += item.id.value
        }
        Diag.log(TAG, "\"${group.name}\": announce check: ${tally.summary(read.values.sumOf { it?.size ?: 0 })}")
        val next = state.copy(freshBy = freshBy, notified = told.takeLast(KEEP), joined = joined)
        put(states() + (group.id.value to next))
        val unreadable = read.count { it.value == null }
        Diag.log(
            TAG,
            "\"${group.name}\": ${freshBy.values.sumOf { it.size }} new since ${Instant.ofEpochMilli(seen)} " +
                "($unreadable of ${read.size} member(s) unreadable, ${joining.size} joined at " +
                "${Instant.ofEpochMilli(joinedAt)}${if (joinedAt == seen) ", the time the group was seen" else ""})",
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

    private class AnnounceTally {
        private var told = 0
        private var undated = 0
        private var beforeJoin = 0
        private var tooOld = 0
        private var announced = 0
        private var quiet = 0
        private var declined = 0

        fun judge(item: MediaItem, floor: Instant, now: Instant, alreadyTold: Boolean): Boolean {
            val at = item.publishedAt
            when {
                alreadyTold -> told++
                at == null -> undated++
                !at.isAfter(floor) -> beforeJoin++
                at.isBefore(now.minus(WINDOW)) -> tooOld++
                else -> return true
            }
            return false
        }

        fun delivered(delivered: Boolean, mutedGroup: Boolean) {
            when {
                mutedGroup -> quiet++
                delivered -> announced++
                else -> declined++
            }
        }

        fun summary(read: Int): String {
            val notAnnounced = told + undated + beforeJoin + tooOld + quiet + declined
            return "read $read item(s): $announced announced, $notAnnounced not announced " +
                "(already announced $told, no date $undated, published before the group or member was first " +
                "looked at $beforeJoin, older than 48 h $tooOld, group's notifications off $quiet, " +
                "notification could not be posted $declined)"
        }
    }

    private companion object {
        const val TAG = "group"
        const val KEEP = 500
        val WINDOW: java.time.Duration = java.time.Duration.ofHours(ANNOUNCE_WINDOW_HOURS)
    }
}

private const val ANNOUNCE_WINDOW_HOURS = 48L

fun GroupNews.diagnostics(): Map<String, String> = states.value.mapKeys { "groups.news.${it.key}" }
    .mapValues { (_, s) ->
        "fresh=${s.fresh.size} notified=${s.notified.size} seenAt=${s.seenAt} members=${s.joined.size}"
    }
