package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MusicRadio(
    private val catalogue: YouTubeMusicCatalogue,
    private val queue: PlaybackQueue,
    private val scope: CoroutineScope,
) {
    private data class Station(
        val seed: RadioSeed,
        val group: QueueGroup,
        val continuation: String?,
        val offered: Set<String>,
        val reachedPlayhead: Boolean = false,
        val exhaustedLogged: Boolean = false,
    )

    @Volatile
    private var station: Station? = null
    private var fetching: Job? = null

    val isActive: Boolean get() = station != null

    val activeGroupId: String? get() = station?.group?.id

    fun describe(): String = station?.let {
        "radio \"${it.group.title}\" seed=${it.seed.playlistId} offered=${it.offered.size} " +
            "continuation=${if (it.continuation != null) "yes" else "none"} reached=${it.reachedPlayhead}"
    } ?: "no radio"

    init {
        scope.launch { queue.state.collect(::onQueueChanged) }
    }

    suspend fun start(seed: RadioSeed, title: String): Boolean {
        val group = QueueGroup(id = "radio:${seed.playlistId}", title = "Radio · $title")
        Diag.log("radio", "starting \"${group.title}\" from ${seed.videoId ?: "-"}/${seed.playlistId}")
        val batch = when (val result = catalogue.radio(seed)) {
            is MusicResult.Failure -> {
                Diag.warn("radio", "could not start \"${group.title}\": ${result.detail}")
                return false
            }
            is MusicResult.Success -> result.value
        }
        if (batch.songs.isEmpty()) {
            Diag.warn("radio", "\"${group.title}\" came back with no songs; nothing queued")
            return false
        }
        val source = MusicSources.radio(seed.playlistId)
        fetching?.cancel()
        station = Station(seed, group, batch.continuation, batch.songs.mapTo(mutableSetOf()) { it.videoId })
        Diag.log(
            "radio",
            "\"${group.title}\" started with ${batch.songs.size} songs, continuation=${batch.continuation != null}",
        )
        queue.playAll(batch.songs.map { it.toPlayable(source) }, group)
        return true
    }

    private fun onQueueChanged(snapshot: QueueSnapshot) {
        val now = station ?: return
        val currentGroup = snapshot.current?.group?.id
        if (currentGroup == now.group.id && !now.reachedPlayhead) station = now.copy(reachedPlayhead = true)
        val live = station ?: return
        when {
            snapshot.entries.none { it.group?.id == live.group.id } -> stop("its songs are no longer in the queue")
            live.reachedPlayhead && snapshot.current != null && currentGroup != live.group.id ->
                stop("now playing \"${snapshot.current?.item?.item?.title}\", which is not part of it")
            else -> considerTopUp(snapshot, live)
        }
    }

    private fun considerTopUp(snapshot: QueueSnapshot, live: Station) {
        val ahead = snapshot.entries.drop((snapshot.currentIndex + 1).coerceAtLeast(0))
            .count { it.group?.id == live.group.id }
        if (ahead > TOP_UP_WHEN_LEFT) return
        if (live.continuation == null) {
            if (!live.exhaustedLogged) {
                Diag.log("radio", "\"${live.group.title}\": $ahead left and YouTube offered no more")
                station = live.copy(exhaustedLogged = true)
            }
            return
        }
        if (fetching?.isActive == true) return
        fetching = scope.launch { topUp(live, ahead, attempt = 1) }
    }

    private suspend fun topUp(from: Station, ahead: Int, attempt: Int) {
        Diag.log("radio", "\"${from.group.title}\": only $ahead left ahead, fetching more (attempt $attempt)")
        val batch = when (val result = catalogue.radio(from.seed, from.continuation)) {
            is MusicResult.Failure -> {
                Diag.warn(
                    "radio",
                    "\"${from.group.title}\": top-up failed (${result.detail}); will retry on the next change"
                )
                return
            }
            is MusicResult.Success -> result.value
        }
        if (station?.group?.id != from.group.id) {
            Diag.log("radio", "\"${from.group.title}\": stopped while fetching; dropping ${batch.songs.size} songs")
            return
        }
        val fresh = batch.songs.filterNot { it.videoId in from.offered }
        val source = MusicSources.radio(from.seed.playlistId)
        val added = queue.appendToGroup(from.group, fresh.map { it.toPlayable(source) })
        val updated = from.copy(
            continuation = batch.continuation,
            offered = from.offered + batch.songs.map { it.videoId }
        )
        station = updated.copy(reachedPlayhead = station?.reachedPlayhead ?: from.reachedPlayhead)
        Diag.log(
            "radio",
            "\"${from.group.title}\": +$added of ${batch.songs.size} " +
                "(${batch.songs.size - fresh.size} offered before), continuation=${batch.continuation != null}",
        )
        if (added == 0 && batch.continuation != null && attempt < MAX_EMPTY_ATTEMPTS) {
            station?.let { topUp(it, ahead, attempt + 1) }
        }
    }

    private fun stop(why: String) {
        val ended = station ?: return
        Diag.log("radio", "\"${ended.group.title}\" stopped topping up: $why")
        fetching?.cancel()
        station = null
    }

    private companion object {
        const val TOP_UP_WHEN_LEFT = 3
        const val MAX_EMPTY_ATTEMPTS = 3
    }
}
