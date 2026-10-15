package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.RecentActions
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MusicRadio(
    private val catalogue: YouTubeMusicCatalogue,
    private val queue: PlaybackQueue,
    private val scope: CoroutineScope,
    private val recent: RecentActions? = null,
    private val file: java.io.File? = null,
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
        scope.launch {
            queue.hydrated.first { it }
            restore()
            queue.state.collect(::onQueueChanged)
        }
    }

    suspend fun start(seed: RadioSeed, title: String): Boolean {
        val group = QueueGroup(id = "$RADIO_GROUP_PREFIX${seed.playlistId}", title = "Radio · $title")
        Diag.log(
            "radio",
            "starting \"${group.title}\" from ${seed.videoId ?: "-"}/${seed.playlistId} params=${seed.params ?: "-"}"
        )
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
        save()
        Diag.log(
            "radio",
            "\"${group.title}\" started with ${batch.songs.size} songs, continuation=${batch.continuation != null}",
        )
        queue.playAll(batch.songs.map { it.toPlayable(source) }, group)
        recent?.record(
            Pin.Radio(
                seed.videoId,
                seed.playlistId,
                title,
                batch.songs.firstOrNull()?.thumbnailUrl?.value,
                seed.params
            ),
            from = "radio",
        )
        return true
    }

    suspend fun startMix(chosen: List<Pair<String, String>>): Boolean {
        val seeds = chosen.distinctBy { it.first }.take(MIX_SEEDS)
        if (seeds.size < chosen.size) {
            Diag.log(
                "radio",
                "mix: ${chosen.size} chosen, the first ${seeds.size} distinct used"
            )
        }
        val title = mixTitle(seeds.map { it.second })
        val radioSeeds = seeds.map { RadioSeed.forSong(it.first) }
        Diag.log("radio", "starting a mix of ${seeds.size} song(s): ${seeds.joinToString { it.first }}")
        val results = coroutineScope { radioSeeds.map { seed -> async { catalogue.radio(seed) } }.awaitAll() }
        results.forEachIndexed { i, result ->
            if (result is MusicResult.Failure) {
                Diag.warn(
                    "radio",
                    "mix: no radio for ${seeds[i].first}: ${result.detail}"
                )
            }
        }
        val batches = results.map { (it as? MusicResult.Success)?.value?.songs.orEmpty() }
        val mixed = interleaveRadios(batches)
        if (mixed.isEmpty()) {
            Diag.warn("radio", "mix \"$title\": none of its seeds gave a radio; nothing queued")
            return false
        }
        val first = radioSeeds.first()
        val group = QueueGroup(
            id = "${RADIO_GROUP_PREFIX}mix:${seeds.joinToString("+") { it.first }}",
            title = "Radio · $title"
        )
        val continuation = (results.first() as? MusicResult.Success)?.value?.continuation
        fetching?.cancel()
        station = Station(first, group, continuation, mixed.mapTo(mutableSetOf()) { it.videoId })
        save()
        Diag.log(
            "radio",
            "mix \"${group.title}\": ${mixed.size} songs from ${batches.count { it.isNotEmpty() }} of ${seeds.size} " +
                "radios (${batches.joinToString { it.size.toString() }}), tops up from the first, " +
                "continuation=${continuation != null}; not added to Recently, where a pin replays one song's radio",
        )
        queue.playAll(mixed.map { it.toPlayable(MusicSources.radio(first.playlistId)) }, group)
        return true
    }

    private fun onQueueChanged(snapshot: QueueSnapshot) {
        val now = station ?: return
        val currentGroup = snapshot.current?.group?.id
        if (currentGroup == now.group.id && !now.reachedPlayhead) station = now.copy(reachedPlayhead = true)
        val live = station ?: return
        when {
            snapshot.entries.none { it.group?.id == live.group.id } -> stop("its songs are no longer in the queue")
            live.reachedPlayhead && currentGroup != live.group.id -> if (!awayLogged) {
                awayLogged = true
                Diag.log("radio", "\"${live.group.title}\": playing something else, so not topping up until it is back")
            }
            else -> {
                awayLogged = false
                considerTopUp(snapshot, live)
            }
        }
    }

    private var awayLogged = false

    private fun considerTopUp(snapshot: QueueSnapshot, live: Station) {
        val ahead = snapshot.entries.drop((snapshot.currentIndex + 1).coerceAtLeast(0))
            .count { it.group?.id == live.group.id }
        if (ahead > TOP_UP_WHEN_LEFT) return
        if (fetching?.isActive == true) return
        val reseedFrom = snapshot.entries.lastOrNull { it.group?.id == live.group.id }?.item?.item?.id?.value
        if (live.continuation == null && (live.exhaustedLogged || reseedFrom == null)) return
        fetching = scope.launch {
            if (live.continuation == null) {
                Diag.log(
                    "radio",
                    "\"${live.group.title}\": YouTube offered no more, so carrying on from its last song $reseedFrom",
                )
                topUp(live.copy(seed = RadioSeed.forSong(checkNotNull(reseedFrom))), ahead, attempt = 1)
            } else {
                topUp(live, ahead, attempt = 1)
            }
        }
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
        val added = queue.addToGroup(from.group, fresh.map { it.toPlayable(source) })
        val updated = from.copy(
            continuation = batch.continuation,
            offered = from.offered + batch.songs.map { it.videoId }
        )
        station = updated.copy(
            reachedPlayhead = station?.reachedPlayhead ?: from.reachedPlayhead,
            exhaustedLogged = added == 0 && batch.continuation == null,
        )
        save()
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
        save()
    }

    private fun save() {
        val file = file ?: return
        val now = station
        runCatching {
            if (now == null) {
                file.delete()
            } else {
                file.writeText(
                    listOf(
                        now.seed.videoId.orEmpty(),
                        now.seed.playlistId,
                        now.seed.params.orEmpty(),
                        now.group.id,
                        now.group.title,
                        now.continuation.orEmpty(),
                        now.offered.joinToString(","),
                    ).joinToString("\n"),
                )
            }
        }.onFailure { Diag.warn("radio", "radio not saved: $it") }
    }

    private fun restore() {
        val lines = file?.takeIf { it.exists() }?.let { runCatching { it.readLines() }.getOrNull() } ?: return
        if (lines.size < Saved.entries.size) {
            return Diag.warn(
                "radio",
                "saved radio unreadable (${lines.size} lines); ignoring it"
            )
        }
        fun line(at: Saved) = lines[at.ordinal]
        val group = QueueGroup(line(Saved.GROUP_ID), line(Saved.GROUP_TITLE))
        if (queue.state.value.entries.none { it.group?.id == group.id }) {
            Diag.log("radio", "saved radio \"${group.title}\" is no longer in the queue; not restoring it")
            file?.delete()
            return
        }
        station = Station(
            seed = RadioSeed(
                line(Saved.VIDEO).ifEmpty { null },
                line(Saved.PLAYLIST),
                line(Saved.PARAMS).ifEmpty { null },
            ),
            group = group,
            continuation = line(Saved.CONTINUATION).ifEmpty { null },
            offered = line(Saved.OFFERED).split(',').filter { it.isNotEmpty() }.toSet(),
            reachedPlayhead = true,
        )
        Diag.log("radio", "restored \"${group.title}\" after a restart: ${describe()}")
    }

    private companion object {
        const val TOP_UP_WHEN_LEFT = 3
        const val MAX_EMPTY_ATTEMPTS = 3
    }
}

const val RADIO_GROUP_PREFIX: String = "radio:"
private const val MIX_SEEDS = 10

private enum class Saved { VIDEO, PLAYLIST, PARAMS, GROUP_ID, GROUP_TITLE, CONTINUATION, OFFERED }
