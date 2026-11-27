package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.data.discover.DiscoverArtist
import com.dewijones92.totum.data.discover.DiscoverPick
import com.dewijones92.totum.data.discover.DiscoverStep
import com.dewijones92.totum.data.discover.DiscoverWalk
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.RecentActions
import com.dewijones92.totum.queue.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class MusicRadio(
    private val catalogue: YouTubeMusicCatalogue,
    private val queue: PlaybackQueue,
    private val scope: CoroutineScope,
    private val recent: RecentActions? = null,
    private val file: java.io.File? = null,
    private val notForMe: NotForMe = NotForMe(),
    private val discover: Discover? = null,
    private val operations: Operations? = null,
) {
    private fun List<MusicSong>.wanted(why: String): List<MusicSong> =
        notForMe.without(this, "radio $why") { MediaItemId(it.videoId) }

    private data class Station(
        val seed: RadioSeed,
        val group: QueueGroup,
        val continuation: String?,
        val offered: Set<String>,
        val reachedPlayhead: Boolean = false,
        val exhaustedLogged: Boolean = false,
        val walk: DiscoverWalk? = null,
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

    suspend fun start(seed: RadioSeed, title: String): Boolean = startCounting(seed, title) > 0

    private suspend fun startCounting(seed: RadioSeed, title: String): Int {
        val group = QueueGroup(id = "$RADIO_GROUP_PREFIX${seed.playlistId}", title = "Radio · $title")
        Diag.log(
            "radio",
            "starting \"${group.title}\" from ${seed.videoId ?: "-"}/${seed.playlistId} params=${seed.params ?: "-"}"
        )
        val batch = when (val result = catalogue.radio(seed)) {
            is MusicResult.Failure -> {
                Diag.warn("radio", "could not start \"${group.title}\": ${result.detail}")
                return 0
            }
            is MusicResult.Success -> result.value
        }
        if (batch.songs.isEmpty()) {
            Diag.warn("radio", "\"${group.title}\" came back with no songs; nothing queued")
            return 0
        }
        val source = MusicSources.radio(seed.playlistId)
        fetching?.cancel()
        station = Station(seed, group, batch.continuation, batch.songs.mapTo(mutableSetOf()) { it.videoId })
        save()
        Diag.log(
            "radio",
            "\"${group.title}\" started with ${batch.songs.size} songs, continuation=${batch.continuation != null}",
        )
        queue.playAll(
            batch.songs.wanted("start").map { it.toPlayable(source) },
            group,
            reason = PlayReason.Radio(group.title),
        )
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
        return batch.songs.size
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
        queue.playAll(
            mixed.wanted("mix").map { it.toPlayable(MusicSources.radio(first.playlistId)) },
            group,
            reason = PlayReason.Radio(group.title),
        )
        return true
    }

    suspend fun startDiscover(seed: DiscoverSeed): Boolean {
        val engine = discover ?: return false.also {
            Diag.warn("discover", "no discover engine here; \"${seed.title}\" not started")
        }
        val key = "discover:${seed.key}"
        if (operations?.isRunning(key) == true) {
            Diag.log("discover", "\"${seed.title}\" is already being found; ignoring a second tap")
            return false
        }
        var started = false
        operations.tracking(key, OperationKind.DISCOVER, seed.title) { progress ->
            when (val begun = beginDiscover(engine, seed, progress)) {
                is DiscoverStart.Started -> OperationEnd.Done(begun.count).also { started = true }
                is DiscoverStart.RadioInstead -> OperationEnd.Done(
                    begun.count,
                    note = "📻 ${begun.why}, so this song's radio started instead",
                ).also { started = true }
                is DiscoverStart.NoneFound -> OperationEnd.NothingBecause(begun.why)
                is DiscoverStart.Unreachable -> OperationEnd.Failed("couldn't reach YouTube Music (${begun.why})")
            }
        }
        return started
    }

    private sealed interface DiscoverStart {
        data class Started(val count: Int) : DiscoverStart
        data class NoneFound(val why: String) : DiscoverStart
        data class RadioInstead(val count: Int, val why: String) : DiscoverStart
        data class Unreachable(val why: String) : DiscoverStart
    }

    private suspend fun beginDiscover(
        engine: Discover,
        seed: DiscoverSeed,
        progress: Operations.Progress?,
    ): DiscoverStart {
        var resolved = engine.resolve(seed)
        if (resolved is Discover.Resolved.Unreachable) {
            Diag.warn("discover", "\"${seed.title}\": could not look the seed up (${resolved.why}); trying once more")
            delay(DISCOVER_RETRY_PAUSE_MS)
            resolved = engine.resolve(seed)
        }
        val first = when (resolved) {
            is Discover.Resolved.Found -> resolved.artist
            Discover.Resolved.NotFound -> return DiscoverStart.NoneFound("no artist found to start from")
            is Discover.Resolved.Unreachable -> return DiscoverStart.Unreachable(resolved.why)
        }
        val begun = firstWalk(engine, seed, first, progress)
        val artist = begun.artist
        val walk = begun.walk
        val step = begun.step
        if (step.picks.isEmpty() && step.failure == null && walk.seedSimilarCount == 0) {
            songRadioInstead(seed, artist, step)?.let { return it }
        }
        val group = QueueGroup(
            id = "$DISCOVER_GROUP_PREFIX${artist.id}",
            title = "$DISCOVER_MARK from ${seed.title}",
        )
        if (step.picks.isEmpty()) {
            Diag.warn(
                "discover",
                "\"${group.title}\": nothing queued near ${artist.name} (dry=${step.dry}, failure=${step.failure}, " +
                    "reason=${step.reason})",
            )
            return step.failure?.let { DiscoverStart.Unreachable(it) }
                ?: DiscoverStart.NoneFound(step.reason ?: "nothing new near ${artist.name}")
        }
        val songs = step.picks.map { it.song }
        fetching?.cancel()
        station = Station(
            seed = RadioSeed(null, "discover:${artist.id}"),
            group = group,
            continuation = null,
            offered = songs.mapTo(mutableSetOf()) { it.videoId },
            walk = walk,
        )
        save()
        Diag.log("discover", "\"${group.title}\" started with ${songs.size} song(s) near ${artist.name} (${artist.id})")
        queue.playAll(
            songs.map { it.toPlayable(MusicSources.discover(artist.id)) },
            group,
            reason = PlayReason.Radio(group.title),
        )
        progress?.report(playing = true)
        recent?.record(
            Pin.Discover(artist.id, artist.name, seed.title, songs.firstOrNull()?.thumbnailUrl?.value),
            from = "discover",
        )
        return DiscoverStart.Started(songs.size)
    }

    private class CreditedWalk(val artist: DiscoverArtist, val walk: DiscoverWalk, val step: DiscoverStep)

    private suspend fun firstWalk(
        engine: Discover,
        seed: DiscoverSeed,
        first: DiscoverArtist,
        progress: Operations.Progress?,
    ): CreditedWalk {
        val walk = engine.walkFrom(first, seed.title)
        var found = 0
        val onPick: (DiscoverPick) -> Unit = { progress?.report(found = ++found) }
        var step = engine.step(walk, DiscoverWalk.PAGES_PER_TOP_UP, onPick)
        if (step.picks.isEmpty() && step.failure != null) {
            Diag.warn(
                "discover",
                "\"$DISCOVER_MARK from ${seed.title}\": YouTube Music did not answer (${step.failure}); " +
                    "trying once more",
            )
            delay(DISCOVER_RETRY_PAUSE_MS)
            step = engine.step(walk, DiscoverWalk.PAGES_PER_TOP_UP, onPick)
        }
        val seedListsNobody = step.picks.isEmpty() && step.failure == null && walk.seedSimilarCount == 0
        if (seedListsNobody) creditedArtistWalk(engine, seed, first, onPick)?.let { return it }
        return CreditedWalk(first, walk, step)
    }

    private suspend fun creditedArtistWalk(
        engine: Discover,
        seed: DiscoverSeed,
        tried: DiscoverArtist,
        onPick: (DiscoverPick) -> Unit,
    ): CreditedWalk? {
        for (other in engine.otherCredits(seed, tried)) {
            val walk = engine.walkFrom(other, seed.title)
            val step = engine.step(walk, DiscoverWalk.PAGES_PER_TOP_UP, onPick)
            Diag.log(
                "discover",
                "\"${seed.title}\": credited artist ${other.name} (${other.id}) gave ${step.picks.size} " +
                    "song(s), reason=${step.reason}",
            )
            if (step.picks.isNotEmpty()) return CreditedWalk(other, walk, step)
        }
        return null
    }

    private suspend fun songRadioInstead(
        seed: DiscoverSeed,
        artist: DiscoverArtist,
        step: DiscoverStep,
    ): DiscoverStart.RadioInstead? {
        val songId = seed.songId ?: return null
        val why = step.reason ?: "no similar artists for ${artist.name}"
        val count = startCounting(RadioSeed.forSong(songId), seed.title)
        Diag.log("discover", "\"${seed.title}\": $why; started the song's own radio instead ($count song(s))")
        return if (count > 0) DiscoverStart.RadioInstead(count, why) else null
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
        if (live.walk != null) return considerDiscoverTopUp(live, ahead)
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

    private fun considerDiscoverTopUp(live: Station, ahead: Int) {
        if (live.exhaustedLogged) return
        fetching = scope.launch(PoliteGate.background()) { discoverTopUp(live, ahead, attempt = 1) }
    }

    private suspend fun discoverTopUp(from: Station, ahead: Int, attempt: Int) {
        val walk = from.walk ?: return
        val engine = discover ?: return
        Diag.log(
            "discover",
            "\"${from.group.title}\": only $ahead left ahead, finding more " +
                "(attempt $attempt of $MAX_DISCOVER_ATTEMPTS)",
        )
        val step = engine.step(walk, DiscoverWalk.PAGES_PER_TOP_UP)
        val now = station
        if (now?.group?.id != from.group.id) {
            Diag.log("discover", "\"${from.group.title}\": stopped while finding; dropping ${step.picks.size} song(s)")
            return
        }
        val songs = step.picks.map { it.song }
        val added = queue.addToGroup(
            from.group,
            songs.map { it.toPlayable(MusicSources.discover(walk.seed.id)) },
            byYou = false,
        )
        station = now.copy(offered = now.offered + songs.map { it.videoId }, exhaustedLogged = step.dry)
        save()
        Diag.log(
            "discover",
            "\"${from.group.title}\": +$added of ${songs.size} from ${step.pagesAsked} page(s) asked" +
                (step.failure?.let { "; couldn't reach YouTube Music ($it), will try again" } ?: "") +
                if (step.dry) "; nothing more in range, so no more top-ups until it is restarted" else "",
        )
        val stillShort = ahead + added <= TOP_UP_WHEN_LEFT
        if (stillShort && !step.dry && attempt < MAX_DISCOVER_ATTEMPTS) {
            delay(DISCOVER_PACE_MS)
            discoverTopUp(from, ahead + added, attempt + 1)
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
        val added = queue.addToGroup(from.group, fresh.wanted("top-up").map { it.toPlayable(source) }, byYou = false)
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
                        if (now.walk != null) DISCOVER_KIND else "",
                        now.walk?.seed?.id.orEmpty(),
                        now.walk?.seed?.name.orEmpty(),
                    ).joinToString("\n"),
                )
            }
        }.onFailure { Diag.warn("radio", "radio not saved: $it") }
    }

    private fun restore() {
        val lines = file?.takeIf { it.exists() }?.let { runCatching { it.readLines() }.getOrNull() } ?: return
        if (lines.size < Saved.KIND.ordinal) {
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
        val offered = line(Saved.OFFERED).split(',').filter { it.isNotEmpty() }.toSet()
        val walk = restoredWalk(lines, group, offered)
        if (lines.getOrNull(Saved.KIND.ordinal) == DISCOVER_KIND && walk == null) {
            Diag.log("discover", "saved discover \"${group.title}\" cannot be rebuilt here; not restoring it")
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
            offered = offered,
            reachedPlayhead = true,
            walk = walk,
        )
        Diag.log("radio", "restored \"${group.title}\" after a restart: ${describe()}")
    }

    private fun restoredWalk(lines: List<String>, group: QueueGroup, offered: Set<String>): DiscoverWalk? {
        if (lines.getOrNull(Saved.KIND.ordinal) != DISCOVER_KIND) return null
        val engine = discover ?: return null
        val id = lines.getOrNull(Saved.ARTIST_ID.ordinal)?.takeIf { it.isNotEmpty() } ?: return null
        val name = lines.getOrNull(Saved.ARTIST_NAME.ordinal).orEmpty().ifEmpty { id }
        val played = queue.state.value.entries.filter { it.group?.id == group.id }.mapNotNull { entry ->
            entry.item.item.sourceUrl?.youTubeChannelId?.let { DiscoverArtist(it, entry.item.item.author ?: it) }
        }
        val title = group.title.removePrefix("$DISCOVER_MARK from ")
        return engine.walkFrom(DiscoverArtist(id, name), title, offered, played)
    }

    private companion object {
        const val TOP_UP_WHEN_LEFT = 3
        const val MAX_EMPTY_ATTEMPTS = 3
        const val MAX_DISCOVER_ATTEMPTS = 3
        const val DISCOVER_PACE_MS = 1_500L
        const val DISCOVER_RETRY_PAUSE_MS = 3_000L
        const val DISCOVER_KIND = "discover"
    }
}

const val RADIO_GROUP_PREFIX: String = "radio:"
const val DISCOVER_GROUP_PREFIX: String = "radio:discover:"
const val DISCOVER_MARK: String = "🔭 Discover"
private const val MIX_SEEDS = 10

private enum class Saved {
    VIDEO,
    PLAYLIST,
    PARAMS,
    GROUP_ID,
    GROUP_TITLE,
    CONTINUATION,
    OFFERED,
    KIND,
    ARTIST_ID,
    ARTIST_NAME,
}
