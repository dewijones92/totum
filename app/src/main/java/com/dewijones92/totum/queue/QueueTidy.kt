package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.download.clearFailed
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.targets.toQueueEntry
import com.dewijones92.totum.data.targets.toTargetEntry
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.listening
import com.dewijones92.totum.playback.PlaybackProgressStore
import kotlinx.coroutines.flow.first

internal enum class TidyKeep(val said: String) {
    MUSIC("music"),
    LOCKED("locked"),
    PLAYING("playing now"),
    PLAYING_GROUP("in the playing group"),
    NOT_FINISHED("not finished"),
}

internal fun tidyKeepReason(
    entry: QueueEntry,
    snapshot: QueueSnapshot,
    states: Map<MediaItemId, PlayState>,
    locked: Set<MediaItemId>,
): TidyKeep? {
    val id = entry.item.item.id
    val playing = snapshot.current
    return when {
        entry.item.item.listening == Listening.MUSIC -> TidyKeep.MUSIC
        id in locked -> TidyKeep.LOCKED
        playing?.item?.item?.id == id -> TidyKeep.PLAYING
        playing?.group?.id != null && entry.group?.id == playing.group?.id -> TidyKeep.PLAYING_GROUP
        states[id]?.isPlayed != true -> TidyKeep.NOT_FINISHED
        else -> null
    }
}

class QueueTidy(
    private val queue: PlaybackQueue,
    private val progress: PlaybackProgressStore,
    private val downloads: DownloadManager,
    private val locked: () -> Set<MediaItemId>,
    private val now: () -> Long = System::currentTimeMillis,
    private val undoStore: TidyUndoStore = TidyUndoStore(),
) {
    data class Result(val playedMoved: Int, val failedCleared: Int) {
        val movedAnything: Boolean get() = playedMoved + failedCleared > 0
    }

    suspend fun tidy(olderThanDays: Int, why: String): Result {
        val snapshot = queue.state.value
        val states = progress.observeStates().first()
        val held = locked()
        val kept = TidyKeep.entries.associateWith { 0 }.toMutableMap()
        val finished = snapshot.entries.filter { entry ->
            tidyKeepReason(entry, snapshot, states, held)?.also { kept[it] = kept.getValue(it) + 1 } == null
        }
        val cutoff = now() - olderThanDays * MS_PER_DAY
        var undated = 0
        var tooRecent = 0
        val leaving = finished.filter { entry ->
            val playedAt = progress.lastSavedAtEpochMs(entry.item.item.id)
            when {
                playedAt == null -> false.also { undated++ }
                playedAt > cutoff -> false.also { tooRecent++ }
                else -> true
            }
        }
        Diag.log(
            "tidy",
            "dewidebug tidy ($why): ${snapshot.entries.size} queued, ${finished.size} finished talk outside the " +
                "playing group, ${leaving.size} older than $olderThanDays days leave; $tooRecent too recent, " +
                "$undated with no played date (kept); kept for other reasons: " +
                kept.entries.filter { it.value > 0 }.joinToString { "${it.key.said}=${it.value}" }.ifEmpty { "none" },
        )
        leaving.take(LISTED).forEach {
            Diag.log("tidy", "dewidebug leaves the queue: \"${it.item.item.title}\" ${it.item.item.id.value}")
        }
        if (leaving.isNotEmpty()) {
            undoStore.hold(leaving.map { TidyUndoStore.Held(snapshot.entries.indexOf(it), it.toTargetEntry()) })
            queue.removeAll(leaving.mapTo(HashSet()) { it.item.item.id })
        }
        val failed = downloads.clearFailed("queue tidy-up, $why")
        return Result(leaving.size, failed.size).also {
            Diag.log(
                "tidy",
                "dewidebug tidy ($why) done: ${it.playedMoved} played item(s) left the queue, " +
                    "${it.failedCleared} failed download(s) cleared",
            )
        }
    }

    fun undo(why: String): Int {
        val batch = undoStore.take(why) ?: return 0
        val restored = batch.held.sortedBy { it.index }.count { held ->
            held.entry.toQueueEntry()?.also { queue.restoreAt(held.index, it) } != null
        }
        Diag.log("tidy", "dewidebug undo ($why): $restored of ${batch.held.size} tidied item(s) put back in the queue")
        return restored
    }

    fun forgetUndo(why: String) = undoStore.clear(why)

    private companion object {
        const val MS_PER_DAY = 86_400_000L
        const val LISTED = 50
    }
}
