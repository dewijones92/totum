package com.dewijones92.totum.data.group

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One group's members read as a single newest-first feed.
 *
 * Per-member rather than filtering the account's subscriptions feed, which would be a
 * single request: that feed is a *sample* of recent uploads across everything you follow,
 * so a quiet channel in the group can be absent from it entirely, and it does not reliably
 * carry shorts or live. Dewi asked for "the live stream, short, everything" from these
 * particular channels, and the only way to get everything from a channel is to ask that
 * channel.
 *
 * Members are fetched CONCURRENTLY, because the cost is a round trip each and a group of
 * ten read one at a time would be ten round trips of waiting.
 *
 * A member that fails contributes nothing and does not fail the group. A group is a view
 * over several sources and most of it is still worth reading when one is unreachable —
 * but it is logged, because "this channel has nothing new" and "this channel could not be
 * reached" look identical in a merged list and only one of them is a problem.
 */
public class GroupFeed(
    private val items: SourceItems,
    private val file: File?,
    private val io: CoroutineDispatcher,
) {
    public constructor(items: SourceItems) : this(items, null, Dispatchers.IO)

    private val saving = Mutex()
    private val kept = MutableStateFlow(loadKept())

    public val keptLists: StateFlow<Map<SourceGroupId, List<MediaItem>>> = kept.asStateFlow()

    private val inFlight = ConcurrentHashMap<SourceGroupId, Deferred<List<MediaItem>>>()

    public fun refresh(group: SourceGroup, scope: CoroutineScope): Deferred<List<MediaItem>> {
        inFlight[group.id]?.takeIf { it.isActive }?.let {
            Diag.log("group", "\"${group.name}\" is already loading; joining that load")
            return it
        }
        return scope.async { itemsFor(group) }.also { load ->
            inFlight[group.id] = load
            load.invokeOnCompletion { inFlight.remove(group.id, load) }
        }
    }

    public fun lastKnown(group: SourceGroup): List<MediaItem>? = lastKnown(group, kept.value)

    public fun lastKnown(group: SourceGroup, lists: Map<SourceGroupId, List<MediaItem>>): List<MediaItem>? {
        val members = group.members.map { it.id }.toSet()
        return lists[group.id]?.filter { it.sourceId in members }
    }

    public suspend fun itemsFor(group: SourceGroup): List<MediaItem> = coroutineScope {
        val members = group.members
        Diag.log("group", "loading \"${group.name}\" from ${members.size} source(s)")
        val reads = members.map { source -> source to async { itemsForMember(source) } }.map { (source, read) ->
            source to read.await()
        }
        val unreadable = reads.filter { (_, read) -> read == null }.mapTo(mutableSetOf()) { (source, _) -> source.id }
        val fetched = reads.flatMap { (_, read) -> read.orEmpty() }
        // Undated items sort last rather than being dropped: a channel that gives no date
        // is still worth reading, just not worth claiming a position among the dated ones.
        val merged = fetched
            .distinctBy { it.id }
            .sortedByDescending { it.publishedAt }
        Diag.log(
            "group",
            "\"${group.name}\" merged to ${merged.size} item(s) from ${fetched.size} fetched",
        )
        val carried = carriedOver(group, unreadable)
        kept.update { it + (group.id to (merged + carried).sortedByDescending { item -> item.publishedAt }) }
        persist()
        merged
    }

    private fun carriedOver(group: SourceGroup, unreadable: Set<SourceId>): List<MediaItem> {
        if (unreadable.isEmpty()) return emptyList()
        val earlier = kept.value[group.id].orEmpty().filter { it.sourceId in unreadable }
        Diag.log(
            "group",
            "\"${group.name}\": ${unreadable.size} source(s) could not be read, so their ${earlier.size} item(s) " +
                "from the last list stay (a failed read is not an empty channel)",
        )
        return earlier
    }

    private suspend fun itemsForMember(source: MediaSource): List<MediaItem>? =
        runCatching { items.itemsFor(source) }
            .onFailure { Diag.warn("group", "\"${source.title}\" could not be read", it) }
            .getOrNull()

    private fun loadKept(): Map<SourceGroupId, List<MediaItem>> {
        val stored = file?.takeIf { it.exists() } ?: return emptyMap()
        return runCatching {
            JSON.decodeFromString(LISTS, stored.readText()).entries.associate { (id, rows) ->
                SourceGroupId(id) to rows.mapNotNull { it.toPlayable()?.item }
            }
        }.onSuccess { lists ->
            Diag.log(
                "group",
                "dewidebug loaded the kept lists from ${stored.name}: ${lists.size} group(s), " +
                    "${lists.values.sumOf { it.size }} item(s)",
            )
        }.onFailure {
            Diag.warn("group", "dewidebug the kept lists in ${stored.name} could not be read; starting with none", it)
        }.getOrDefault(emptyMap())
    }

    private suspend fun persist() {
        val target = file ?: return
        withContext(io) {
            saving.withLock {
                val snapshot = kept.value
                runCatching {
                    val rows = snapshot.entries.associate { (id, list) ->
                        id.value to list.map { it.asPlayable().toBackup() }
                    }
                    target.writeTextReplacing(JSON.encodeToString(LISTS, rows))
                }.onSuccess {
                    Diag.log(
                        "group",
                        "dewidebug kept ${snapshot.size} group list(s), " +
                            "${snapshot.values.sumOf { it.size }} item(s), in ${target.name}",
                    )
                }.onFailure {
                    Diag.warn("group", "dewidebug the kept lists could not be saved; they stay in memory only", it)
                }
            }
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val LISTS = MapSerializer(String.serializer(), ListSerializer(BackupItem.serializer()))
    }
}
