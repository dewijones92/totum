package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackProgressStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal fun randomStartMs(durationMs: Long, random: Random): Long? {
    val endMargin = minOf(END_MARGIN_MS, durationMs / END_MARGIN_FRACTION)
    val latest = durationMs - endMargin
    return if (latest <= 0) null else random.nextLong(0, latest)
}

class RandomStarts(
    private val inner: PlaybackProgressStore,
    private val file: File?,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackProgressStore by inner {

    private val pending = ConcurrentHashMap(load())

    fun assign(items: List<MediaItem>, why: String) {
        var withoutDuration = 0
        items.forEach { item ->
            val start = item.duration?.inWholeMilliseconds?.let { randomStartMs(it, random) }
            if (start == null) withoutDuration++ else pending[item.id] = start
        }
        Diag.log(
            "shuffle",
            "random starts for $why: ${items.size - withoutDuration} of ${items.size} item(s)" +
                if (withoutDuration > 0) ", $withoutDuration with no known duration start as usual" else "",
        )
        save()
    }

    fun peek(itemId: MediaItemId): Long? = pending[itemId]

    override suspend fun resumePositionMs(itemId: MediaItemId): Long? {
        val start = pending.remove(itemId) ?: return inner.resumePositionMs(itemId)
        Diag.log("shuffle", "${itemId.value} starts at ${start}ms, a random point picked when it was shuffled")
        save()
        return start
    }

    private val writing = Mutex()

    companion object {
        fun saved(inner: PlaybackProgressStore, context: android.content.Context, scope: CoroutineScope): RandomStarts =
            RandomStarts(inner, File(context.filesDir, "random-starts.txt"), scope)
    }

    private fun load(): Map<MediaItemId, Long> = runCatching {
        val file = file?.takeIf { it.exists() } ?: return@runCatching emptyMap()
        file.readLines().mapNotNull { line ->
            val (id, ms) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            ms.toLongOrNull()?.let { MediaItemId(id) to it }
        }.toMap()
    }.getOrElse {
        Diag.warn("shuffle", "random starts unreadable, starting afresh: $it")
        emptyMap()
    }

    private fun save() {
        val file = file ?: return
        scope.launch(io) {
            writing.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(pending.entries.joinToString("") { (id, ms) -> "${id.value}\t$ms\n" })
                }.onFailure { Diag.warn("shuffle", "random starts not saved: $it") }
            }
        }
    }
}

private const val END_MARGIN_MS = 10_000L
private const val END_MARGIN_FRACTION = 10
