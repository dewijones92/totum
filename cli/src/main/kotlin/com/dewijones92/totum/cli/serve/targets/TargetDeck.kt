package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.appendingAll
import com.dewijones92.totum.data.queue.insertingAllNext
import com.dewijones92.totum.data.queue.removingAt
import com.dewijones92.totum.data.targets.DEFAULT_VOLUME
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetReporting
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.toSnapshot
import com.dewijones92.totum.data.targets.toTargetEntry
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.picture
import com.dewijones92.totum.domain.skipsSilence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.random.Random

@Suppress("TooManyFunctions")
internal class TargetDeck(
    private val target: Target,
    private val player: TargetPlayer,
    private val link: TargetLink,
    private val sounds: TargetSounds,
    file: File,
    private val random: Random = Random.Default,
    private val lookAhead: (PlayableItem) -> Unit = {},
    private val reporter: ProgressReporter = ProgressReporter.NONE,
    private val ownerId: () -> String? = { null },
) {
    private val store = DeckStore(file)
    private val lock = Mutex()
    private var queue = QueueSnapshot()
    private var volume = DEFAULT_VOLUME
    private var playing = false
    private var destination: String? = null
    private var problem: String? = null
    private var sound = SoundSettings()
    private val provenance = Provenances()
    private var reachedAhead: String? = null

    init {
        restore()
    }

    fun state(): TargetState = TargetState(
        target = target,
        entries = queue.entries.map { provenance.stamp(it.toTargetEntry()) },
        at = queue.currentIndex,
        playing = playing,
        positionMs = (if (queue.current != null) player.positionMs else null) ?: 0,
        durationMs = queue.current?.let { player.durationMs ?: it.item.item.duration?.inWholeMilliseconds },
        volume = volume,
        linked = destination != null,
        problem = problem,
        sound = sound,
    )

    suspend fun enqueue(request: TargetQueueRequest): EnqueueResult =
        reachingFirst(needsLink = true) { enqueueLocked(request) }

    private suspend fun enqueueLocked(request: TargetQueueRequest): EnqueueResult {
        val wanted = request.playables(random, target.id)
        if (wanted.isEmpty()) {
            Diag.warn("targets", "${target.id}: nothing playable in a ${request.mode} of ${request.items.size}")
            return EnqueueResult.Refused("❌ Nothing in that was playable on ${target.label}")
        }
        val group = request.group
        provenance.note(wanted, request.from, ownerId())
        queue = when (request.mode) {
            QueueMode.REPLACE -> QueueSnapshot(wanted.map { QueueEntry(it, group) })
            QueueMode.NEXT -> queue.insertingAllNext(wanted, group)
            QueueMode.END -> queue.appendingAll(wanted, group)
            QueueMode.PLAY -> queue.insertingAllNext(wanted, group)
        }
        Diag.log(
            "targets",
            "${target.id}: ${request.mode} ${wanted.size} item(s) group=${group?.title} shuffle=${request.shuffle} " +
                "from=${request.from?.label}(owner=${request.from?.id == ownerId()}) " +
                "startItem=${request.startItemId} " +
                "startAtMs=${request.startAtMs} queue=${queue.entries.size} playing=$playing",
        )
        if (request.mode == QueueMode.REPLACE || request.mode == QueueMode.PLAY || !playing) {
            val first = queue.entries.indexOfFirst {
                it.item.item.id.value == (request.startItemId ?: wanted.first().item.id.value)
            }
            startAt(first.coerceAtLeast(0), request.startAtMs.takeIf { !request.shuffle } ?: 0)
        } else {
            save()
        }
        return EnqueueResult.Accepted(state())
    }

    suspend fun control(control: TargetControl): TargetState =
        reachingFirst(needsLink = control.action in STARTING_ACTIONS) { controlLocked(control) }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun controlLocked(control: TargetControl): TargetState {
        Diag.log(
            "targets",
            "${target.id}: ${control.action} index=${control.index} at=${queue.currentIndex} playing=$playing"
        )
        when (control.action) {
            TargetAction.PLAY -> resume()
            TargetAction.PAUSE -> if (playing) {
                reportProgress(finished = false)
                player.pause(true)
                playing = false
            }
            TargetAction.NEXT -> moveTo(queue.place + 1)
            TargetAction.PREVIOUS -> moveTo((queue.place - 1).coerceAtLeast(0))
            TargetAction.JUMP -> control.index?.let { moveTo(it) }
            TargetAction.SEEK -> control.positionMs?.let(player::seek)
            TargetAction.VOLUME -> control.volume?.let {
                volume = it.coerceIn(0, MAX_VOLUME)
                player.volume(volume)
            }
            TargetAction.STOP -> finish("stopped")
            TargetAction.REMOVE -> control.index?.let { remove(it) }
            TargetAction.SOUND -> control.sound?.let { changeSound(it) }
        }
        save()
        return state()
    }

    suspend fun ended(failed: Boolean) = reachingFirst(needsLink = false) { endedLocked(failed) }

    private suspend fun endedLocked(failed: Boolean) {
        if (!playing) {
            Diag.log("targets", "${target.id}: an end with nothing playing, ignored (failed=$failed)")
            return
        }
        Diag.log(
            "targets",
            "${target.id}: ${queue.current?.item?.item?.title} ${if (failed) "failed" else "finished"}, moving on"
        )
        if (!failed) reportProgress(finished = true)
        startAt(queue.currentIndex + 1)
    }

    private suspend fun <T> reachingFirst(needsLink: Boolean, block: suspend () -> T): T {
        val linked = lock.withLock { destination != null }
        val ahead = if (linked || !needsLink) null else link.connect()
        return lock.withLock {
            reachedAhead = ahead
            try {
                block()
            } finally {
                reachedAhead = null
            }
        }
    }

    suspend fun tick() = lock.withLock {
        if (playing) reportProgress(finished = false)
    }

    private suspend fun moveTo(index: Int) {
        if (playing) reportProgress(finished = false)
        startAt(index)
    }

    private suspend fun resume() {
        if (playing) return
        if (queue.current != null && destination != null) {
            player.pause(false)
            playing = true
        } else {
            startAt(queue.place.coerceAtLeast(0))
        }
    }

    private suspend fun changeSound(wanted: SoundSettings) {
        if (wanted == sound) return
        val where = player.positionMs ?: 0
        sound = wanted
        Diag.log(
            "targets",
            "${target.id}: sound -> $wanted, current item ${if (playing) "restarts at ${where}ms" else "unchanged"}"
        )
        if (playing && queue.current != null) startAt(queue.currentIndex, where)
    }

    private suspend fun startAt(index: Int, startAtMs: Long = 0) {
        var at = index
        while (at in queue.entries.indices) {
            val item = queue.entries[at].item
            val picture = target.kind.hasPicture && item.picture != Picture.NO
            val itemSound = sound.copy(skipSilence = item.item.contentKind.skipsSilence(sound.skipSilence))
            val url = sounds.urlFor(item, picture)
            if (url == null) {
                Diag.warn(
                    "targets",
                    "${target.id}: no stream for ${item.item.id.value} (${item.item.title}) " +
                        "picture=$picture, passing over"
                )
                at++
                continue
            }
            val reached = destination ?: reachedAhead?.also { destination = it }
            queue = queue.copy(currentIndex = at)
            if (reached == null) {
                problem = "⚠️ Couldn't reach ${target.label}"
                playing = false
                Diag.warn("targets", "${target.id}: target unreachable, holding at $at")
                save()
                return
            }
            player.play(PlayRequest(url, reached, volume, itemSound, startAtMs, picture, item.item.title))
            playing = true
            problem = null
            Diag.log(
                "targets",
                "${target.id}: playing $at/${queue.entries.size} ${item.item.title} on $reached " +
                    "picture=$picture sound=$itemSound (asked for $sound, kind ${item.item.contentKind}) " +
                    "from=${startAtMs}ms",
            )
            save()
            queue.entries.getOrNull(at + 1)?.let { lookAhead(it.item) }
            return
        }
        finish("reached the end", report = false)
    }

    private suspend fun reportProgress(finished: Boolean) {
        val index = queue.currentIndex
        val entry = state().entries.getOrNull(index) ?: return
        val position = (player.positionMs ?: 0)
        val duration = player.durationMs ?: entry.item.durationMs
        val videoId = TargetReporting.videoIdToReport(entry, signedIn = true)
        Diag.log(
            "targets",
            "${target.id}: progress ${entry.item.itemId} at=${position}ms of=${duration}ms finished=$finished " +
                "youtube=${if (videoId != null) "will report" else TargetReporting.why(entry, signedIn = true)}",
        )
        if (videoId != null) reporter.report(entry, position, duration, finished)
    }

    private suspend fun remove(index: Int) {
        if (index !in queue.entries.indices) return
        val wasCurrent = index == queue.currentIndex
        queue = queue.removingAt(index)
        if (wasCurrent && playing) startAt(index)
    }

    private suspend fun finish(why: String, report: Boolean = true) {
        val wasLinked = destination != null
        if (report && playing) reportProgress(finished = false)
        player.stop()
        playing = false
        if (wasLinked) link.release()
        destination = null
        Diag.log("targets", "${target.id}: $why; target handed back=$wasLinked")
        save()
    }

    private fun save() = store.save(state().copy(playing = false, problem = null), target.id)

    private fun restore() {
        val saved = store.restore(target.id) ?: return
        queue = saved.entries.toSnapshot(saved.at)
        volume = saved.volume
        sound = saved.sound
        provenance.restore(saved.entries)
        Diag.log("targets", "${target.id}: restored ${queue.entries.size} item(s), paused at ${queue.currentIndex}")
    }
}

private const val MAX_VOLUME = 100

private val STARTING_ACTIONS = setOf(TargetAction.PLAY, TargetAction.NEXT, TargetAction.PREVIOUS, TargetAction.JUMP)

private fun TargetQueueRequest.playables(random: Random, targetId: String): List<PlayableItem> = items
    .mapNotNull { backup ->
        backup.toPlayable().also {
            if (it == null) Diag.warn("targets", "$targetId: ${backup.itemId} not playable, left out")
        }
    }
    .let { if (shuffle) it.shuffled(random) else it }

internal sealed interface EnqueueResult {
    data class Accepted(val state: TargetState) : EnqueueResult
    data class Refused(val why: String) : EnqueueResult
}

internal class Provenances {
    private class Who(val addedBy: String?, val reports: Boolean)

    private val byItem = HashMap<String, Who>()

    fun note(items: List<PlayableItem>, from: Requester?, ownerId: String?) {
        items.forEach { byItem[it.item.id.value] = Who(from?.label, TargetReporting.reports(from, ownerId)) }
    }

    fun restore(entries: List<TargetEntry>) {
        entries.forEach { byItem[it.item.itemId] = Who(it.addedBy, it.reportsToYouTube) }
    }

    fun stamp(entry: TargetEntry): TargetEntry {
        val who = byItem[entry.item.itemId]
        return entry.copy(addedBy = who?.addedBy, reportsToYouTube = who?.reports == true)
    }
}
