package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

fun aheadFor(
    snapshot: QueueSnapshot,
    keep: Map<String, Int>,
    present: (MediaItemId) -> Boolean,
    finished: (PlayableItem) -> Boolean,
): List<PlayableItem> = keep.flatMap { (groupId, count) ->
    val members = snapshot.entries.withIndex().filter { it.value.group?.id == groupId }
    val playingHere = snapshot.current?.group?.id == groupId
    val from = if (playingHere) snapshot.currentIndex + 1 else 0
    members.asSequence()
        .filter { it.index >= from }
        .map { it.value.item }
        .filter { !finished(it) }
        .take(count)
        .filter { !present(it.item.id) }
        .toList()
}

class HomeAhead(
    private val file: File?,
    private val scope: CoroutineScope,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableKeep = MutableStateFlow(load())
    val keep: StateFlow<Map<String, Int>> = mutableKeep.asStateFlow()
    private val asked = mutableSetOf<MediaItemId>()

    fun choose(groupId: String, count: Int?, why: String) {
        Diag.log(TAG, "group $groupId keeps ${count ?: "nothing"} ahead at home ($why)")
        mutableKeep.update { if (count == null) it - groupId else it + (groupId to count) }
        save()
    }

    fun start(
        queue: StateFlow<QueueSnapshot>,
        present: Flow<Set<MediaItemId>>,
        finished: (PlayableItem) -> Boolean,
        send: (PlayableItem) -> Unit,
    ) {
        scope.launch {
            combine(queue, keep, present) { snapshot, keep, there -> Triple(snapshot, keep, there) }
                .collect { (snapshot, keep, there) ->
                    asked.retainAll { it !in there }
                    if (keep.isEmpty()) return@collect
                    val wanted = aheadFor(snapshot, keep, { it in there || it in asked }, finished)
                    if (wanted.isNotEmpty()) {
                        Diag.log(
                            TAG,
                            "sending ${wanted.size} home ahead: ${wanted.joinToString { "\"${it.item.title}\"" }}"
                        )
                    }
                    wanted.forEach {
                        asked += it.item.id
                        send(it)
                    }
                }
        }
    }

    private fun load(): Map<String, Int> = runCatching {
        if (file == null || !file.exists()) return@runCatching emptyMap()
        file.readLines().mapNotNull { line ->
            val (id, count) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            count.toIntOrNull()?.let { id to it }
        }.toMap()
    }.getOrElse {
        Diag.warn(TAG, "home-ahead choices unreadable, starting afresh: $it")
        emptyMap()
    }

    private val writing = Mutex()

    private fun save() {
        val file = file ?: return
        scope.launch(io) {
            writing.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(mutableKeep.value.entries.joinToString("") { (id, n) -> "$id\t$n\n" })
                }.onFailure { Diag.warn(TAG, "home-ahead choices not saved: $it") }
            }
        }
    }

    private companion object {
        const val TAG = "home"
    }
}
