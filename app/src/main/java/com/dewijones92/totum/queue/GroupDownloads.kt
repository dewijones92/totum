package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.torrent.hasAudioOnlyFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class GroupDownload { ASK, YES, NO }

internal fun Map<String, GroupDownload>.trackedFor(
    groupsInQueue: Set<String>,
    firstRun: Boolean,
): Map<String, GroupDownload> {
    val arriving = if (firstRun) GroupDownload.YES else GroupDownload.ASK
    return groupsInQueue.associateWith { this[it] ?: arriving }
}

class GroupDownloads(
    private val file: File?,
    private val scope: CoroutineScope,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val firstRun = file != null && !file.exists()
    private val mutableChoices = MutableStateFlow(load())
    val choices: StateFlow<Map<String, GroupDownload>> = mutableChoices.asStateFlow()

    fun start(queue: StateFlow<QueueSnapshot>, hydrated: StateFlow<Boolean>) {
        scope.launch {
            hydrated.first { it }
            queue.collect(::track)
        }
    }

    private var seeded = !firstRun

    fun track(snapshot: QueueSnapshot) {
        val groups = snapshot.entries.mapNotNull { it.group?.id }.toSet()
        val before = mutableChoices.value
        val seeding = !seeded
        seeded = true
        val after = before.trackedFor(groups, firstRun = seeding)
        if (seeding) Diag.log("download", "first run: ${groups.size} group(s) already queued keep downloading")
        if (after == before && !seeding) return
        (after.keys - before.keys).forEach { id ->
            Diag.log("download", "group $id joined the queue: ${after[id]} (${snapshot.count(id)} item(s))")
        }
        (before.keys - after.keys).forEach { id ->
            Diag.log(
                "download",
                "group $id left the queue; its choice is forgotten"
            )
        }
        mutableChoices.value = after
        save()
    }

    fun choose(groupId: String, choice: GroupDownload, why: String) {
        Diag.log("download", "group $groupId download -> $choice ($why)")
        mutableChoices.update { it + (groupId to choice) }
        save()
    }

    fun allows(groupId: String): Boolean = choices.value[groupId] == GroupDownload.YES

    fun fetchesAutomatically(entry: QueueEntry): Boolean = fetchesAutomatically(entry, choices.value)

    companion object {
        fun startedFor(queue: PlaybackQueue, context: android.content.Context, scope: CoroutineScope): GroupDownloads =
            GroupDownloads(
                File(context.filesDir, "group-downloads.txt"),
                scope
            ).also { it.start(queue.state, queue.hydrated) }

        fun fetchesAutomatically(entry: QueueEntry, choices: Map<String, GroupDownload>): Boolean =
            entry.item.hasAudioOnlyFetch && entry.group?.id?.let { choices[it] == GroupDownload.YES } != false
    }

    private fun load(): Map<String, GroupDownload> = runCatching {
        if (file == null || !file.exists()) return@runCatching emptyMap()
        file.readLines().mapNotNull { line ->
            val (id, choice) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            GroupDownload.entries.firstOrNull { it.name == choice }?.let { id to it }
        }.toMap()
    }.getOrElse {
        Diag.warn("download", "group download choices unreadable, starting afresh: $it")
        emptyMap()
    }

    private val writing = Mutex()

    private fun save() {
        val file = file ?: return
        scope.launch(io) {
            writing.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(
                        mutableChoices.value.entries.joinToString("") { (id, choice) -> "$id\t${choice.name}\n" }
                    )
                }.onFailure { Diag.warn("download", "group download choices not saved: $it") }
            }
        }
    }
}

private fun QueueSnapshot.count(groupId: String) = entries.count { it.group?.id == groupId }
