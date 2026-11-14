package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.appendingAll
import com.dewijones92.totum.data.queue.insertingAllNext
import com.dewijones92.totum.data.queue.removingAll
import com.dewijones92.totum.data.queue.removingAt
import com.dewijones92.totum.data.targets.KeepAwake
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetReporting
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.data.targets.nextAfterEnd
import com.dewijones92.totum.data.targets.onATarget
import com.dewijones92.totum.data.targets.toSnapshot
import com.dewijones92.totum.data.targets.toTargetEntry
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.domain.picture
import com.dewijones92.totum.domain.skipsSilence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

@Suppress("TooManyFunctions")
internal class TargetDeck(
    initial: Target,
    private val player: TargetPlayer,
    private val link: TargetLink,
    private val sounds: TargetSounds,
    file: File,
    private val random: Random = Random.Default,
    private val lookAhead: (PlayableItem) -> Unit = {},
    private val reporter: ProgressReporter = ProgressReporter.NONE,
    private val ownerId: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val volumeControl: TargetVolume? = null,
    private val keeper: TargetKeeper? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Volatile
    private var target: Target = initial
    private val store = DeckStore(file)
    private val lock = Mutex()
    private var queue = QueueSnapshot()

    @Volatile
    private var playing = false
    private var destination: String? = null
    private var problem: Problem? = null
    private var starting = false
    private var pending: PendingStart? = null
    private var placeMs = 0L
    private var lastSeenMs: Long? = null
    private var movedAtMs = 0L
    private var linkMs = 0L
    private var sinkWaitMs = 0L
    private var resolveMs = 0L
    private var sound = SoundSettings()
    private var repeat = RepeatMode.OFF
    private var startedAtMs = 0L
    private val provenance = Provenances()
    private var reachedAhead: String? = null
    private val sleep = DeckSleep(
        label = target.label,
        scope = scope,
        clock = clock,
        player = player,
        volume = volumeControl,
        playback = {
            SleepPlayback(
                queue.current?.itemId(),
                player.positionMs,
                player.durationMs ?: queue.current?.item?.item?.duration?.inWholeMilliseconds,
                isLive(),
            )
        },
        pause = { lock.withLock { pausePlaying().also { save() } } },
    )

    init {
        restore()
    }

    fun state(): TargetState = TargetState(
        target = target,
        entries = queue.entries.map { provenance.stamp(it.toTargetEntry()) },
        at = queue.currentIndex,
        playing = playing,
        positionMs = (if (queue.current != null) player.positionMs else null) ?: placeMs,
        durationMs = queue.current?.takeUnless { isLive() }
            ?.let { player.durationMs ?: it.item.item.duration?.inWholeMilliseconds },
        volume = volumeNow(),
        volumeMode = volumeControl?.mode,
        keepAwake = keeper?.state,
        linked = destination != null,
        problem = problem?.text ?: player.notice,
        sound = sound,
        repeat = repeat,
        live = isLive(),
        power = link.power ?: player.power,
        switchedOnByHand = link.switchedOnByHand,
        starting = starting,
        trouble = problem?.trouble,
        sleep = sleep.state(),
    )

    private fun volumeNow(): Int? = volumeControl?.percent ?: player.volumePercent

    private fun isLive(): Boolean = queue.current?.item?.item?.contentKind == MediaContentKind.LIVE || player.live

    suspend fun enqueue(request: TargetQueueRequest): EnqueueResult {
        val accepted = accept(request)
        if (accepted is EnqueueResult.Accepted) startPending()
        return if (accepted is EnqueueResult.Accepted) EnqueueResult.Accepted(state()) else accepted
    }

    suspend fun accept(request: TargetQueueRequest): EnqueueResult = lock.withLock { acceptLocked(request) }

    private fun acceptLocked(request: TargetQueueRequest): EnqueueResult {
        val wanted = request.playables(random, target.id)
        if (wanted.isEmpty()) {
            Diag.warn("targets", "${target.id}: nothing playable in a ${request.mode} of ${request.items.size}")
            return EnqueueResult.Refused("❌ Nothing in that was playable on ${target.label}")
        }
        val group = request.group
        problem = null
        request.repeat?.let(::setRepeat)
        provenance.note(wanted, request.from, ownerId(), request.loop)
        queue = when (request.mode) {
            QueueMode.REPLACE -> QueueSnapshot(wanted.map { QueueEntry(it, group) })
            QueueMode.NEXT -> queue.insertingAllNext(wanted, group)
            QueueMode.END -> queue.appendingAll(wanted, group)
            QueueMode.PLAY -> queue.insertingAllNext(wanted, group)
        }
        val starts = request.mode == QueueMode.REPLACE || request.mode == QueueMode.PLAY || !playing
        Diag.log(
            "targets",
            "${target.id}: ${request.mode} ${wanted.size} item(s) group=${group?.title} shuffle=${request.shuffle} " +
                "from=${request.from?.label}(owner=${request.from?.id == ownerId()}) " +
                "startItem=${request.startItemId} " +
                "startAtMs=${request.startAtMs} queue=${queue.entries.size} playing=$playing " +
                "accepted, ${if (starts) "starting it next" else "nothing to start"}",
        )
        if (starts) {
            val first = queue.entries.indexOfFirst {
                it.item.item.id.value == (request.startItemId ?: wanted.first().item.id.value)
            }
            pending = PendingStart(first.coerceAtLeast(0), request.startAtMs.takeIf { !request.shuffle } ?: 0)
            starting = true
        }
        save()
        return EnqueueResult.Accepted(state())
    }

    suspend fun startPending() {
        if (lock.withLock { pending == null }) return
        reachingFirst(needsLink = true) { startPendingLocked() }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startPendingLocked() {
        val start = pending ?: return
        pending = null
        val began = clock()
        resolveMs = 0
        try {
            startAt(start.index, start.startAtMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn("targets", "${target.id}: starting item ${start.index} failed", e)
            val entry = queue.entries.getOrNull(start.index)
            problem = TroubleWords.problem(
                target.label,
                TargetTrouble(TroubleKind.COULD_NOT_PLAY, entry?.itemId(), "${e.message}"),
                entry?.item?.item?.title,
            )
            playing = false
        } finally {
            starting = pending != null
        }
        Diag.log(
            "targets",
            "${target.id}: start of item ${start.index} done, playing=$playing " +
                "link=${linkMs}ms sinkWait=${sinkWaitMs}ms resolve=${resolveMs}ms total=${clock() - began + linkMs}ms",
        )
    }

    suspend fun control(control: TargetControl): TargetState =
        reachingFirst(needsLink = control.action in STARTING_ACTIONS) { controlLocked(control) }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun controlLocked(control: TargetControl): TargetState {
        Diag.log(
            "targets",
            "${target.id}: ${control.action} index=${control.index} at=${queue.currentIndex} playing=$playing"
        )
        val index = if (control.action in INDEXED_ACTIONS) indexOf(control) else control.index
        when (control.action) {
            TargetAction.PLAY -> {
                problem = null
                resume()
            }
            TargetAction.PAUSE -> pausePlaying()
            TargetAction.NEXT -> moveTo(queue.place + 1)
            TargetAction.PREVIOUS -> moveTo((queue.place - 1).coerceAtLeast(0))
            TargetAction.JUMP -> index?.let { moveTo(it) }
            TargetAction.SEEK -> control.positionMs?.let {
                player.seek(it)
                placeMs = it
                lastSeenMs = null
            }
            TargetAction.VOLUME -> control.volume?.let {
                val wanted = it.coerceIn(0, MAX_VOLUME)
                val followed = sleep.personSetVolume(wanted)
                Diag.log("targets", "${target.id}: volume set to $wanted from the phone, followed by a fade=$followed")
                if (!followed) volumeControl?.set(wanted) ?: player.volume(wanted)
            }
            TargetAction.STOP -> {
                problem = null
                finish("stopped")
            }
            TargetAction.REMOVE -> control.itemIds?.let { removeIds(it) } ?: index?.let { remove(it) }
            TargetAction.SOUND -> control.sound?.let { changeSound(it) }
            TargetAction.REPEAT -> control.repeat?.let(::setRepeat)
            TargetAction.WAKE -> woken()
            TargetAction.SLEEP -> control.sleep?.let { armSleep(it) }
        }
        save()
        return state()
    }

    private suspend fun pausePlaying() {
        if (!playing) return
        reportProgress(finished = false)
        captureThePlace()
        player.pause(true)
        playing = false
    }

    private suspend fun armSleep(ask: TargetSleepAsk) {
        when (ask.kind) {
            SleepKind.OFF -> sleep.release("cancelled from the phone")
            SleepKind.AFTER_TIME -> if (ask.seconds > 0) {
                sleep.arm(ask)
            } else {
                Diag.warn("targets", "${target.id}: a sleep timer of ${ask.seconds}s is not a timer, ignored")
            }
            SleepKind.AFTER_ITEMS -> {
                val current = queue.current?.itemId()
                if (current == null || current !in ask.itemIds) {
                    Diag.warn(
                        "targets",
                        "${target.id}: sleep after ${ask.itemIds.size} item(s) refused, ${current ?: "nothing"} " +
                            "is playing and it is not among them; the phone's list is out of date",
                    )
                    throw StaleEdit(state())
                }
                sleep.arm(ask)
            }
        }
    }

    private fun indexOf(control: TargetControl): Int? {
        val wanted = control.itemId ?: return control.index
        val ids = queue.entries.map { it.item.item.id.value }
        val candidates = ids.indices.filter { ids[it] == wanted }
        val asked = control.index
        val chosen = asked?.takeIf { it in candidates }
            ?: candidates.minByOrNull { abs(it - (asked ?: queue.currentIndex.coerceAtLeast(0))) }
        if (chosen == null) {
            Diag.warn(
                "targets",
                "${target.id}: ${control.action} of $wanted at ${control.index} refused: it is not in the queue " +
                    "(${ids.size} item(s)); the phone's list is out of date",
            )
            throw StaleEdit(state())
        }
        if (chosen != asked) {
            Diag.warn(
                "targets",
                "${target.id}: ${control.action} of $wanted was asked at index $asked but it is at $chosen now; " +
                    "acting on $chosen",
            )
        }
        return chosen
    }

    suspend fun ended(failed: Boolean) = reachingFirst(needsLink = false) { endedLocked(failed) }

    private suspend fun endedLocked(failed: Boolean) {
        if (!playing) {
            Diag.log("targets", "${target.id}: an end with nothing playing, ignored (failed=$failed)")
            return
        }
        val title = queue.current?.item?.item?.title
        val playedMs = clock() - startedAtMs
        val loops = queue.current?.item?.item?.id?.value?.let(provenance::loops) == true
        val next = repeat.nextAfterEnd(
            queue.currentIndex,
            queue.entries.size,
            failed,
            playedMs >= MIN_REPEAT_PLAY_MS,
            entryLoops = loops,
        )
        val tooSoon = !failed && playedMs < MIN_REPEAT_PLAY_MS && (repeat != RepeatMode.OFF || loops)
        Diag.log(
            "targets",
            "${target.id}: $title ${if (failed) "failed" else "finished"} after ${playedMs}ms; repeat=$repeat " +
                "entryLoops=$loops, " +
                "going to ${next.takeIf { it < queue.entries.size }?.let { "item $it" } ?: "the end"}" +
                if (tooSoon) " (too soon after starting to repeat)" else "",
        )
        if (failed) {
            val reason = player.failure ?: "the player stopped with an error"
            problem = TroubleWords.problem(
                target.label,
                TargetTrouble(TroubleKind.COULD_NOT_PLAY, queue.current?.itemId(), reason),
                title,
            )
        } else {
            problem = null
            reportProgress(finished = true)
        }
        if (queue.current?.itemId()?.let(sleep::endsWith) == true) {
            if (next in queue.entries.indices) queue = queue.copy(currentIndex = next)
            finish("the sleep timer stopped it after $title", report = false, keepPlace = false)
            return
        }
        startAt(next)
    }

    private fun unreachableProblem(): Problem =
        TroubleWords.problem(target.label, link.unreachable ?: TargetTrouble(TroubleKind.NOT_CONNECTED))

    private suspend fun woken() {
        if (destination != null) {
            Diag.log("targets", "${target.id}: wake asked, but it is already linked")
            return
        }
        val reached = reachedAhead
        if (reached == null) {
            problem = unreachableProblem()
            Diag.warn("targets", "${target.id}: wake asked, ${problem?.text}")
            return
        }
        problem = null
        link.release()
        Diag.log("targets", "${target.id}: woken and reachable at $reached; nothing queued to play")
    }

    private fun setRepeat(wanted: RepeatMode) {
        if (!wanted.onATarget) {
            Diag.warn("targets", "${target.id}: repeat $wanted is not offered on a target yet, keeping $repeat")
            return
        }
        Diag.log("targets", "${target.id}: repeat $repeat -> $wanted")
        repeat = wanted
    }

    private suspend fun <T> reachingFirst(needsLink: Boolean, block: suspend () -> T): T {
        val linked = lock.withLock { destination != null }
        val began = clock()
        val ahead = if (linked || !needsLink) null else link.connect()
        val connectedFor = clock() - began
        return lock.withLock {
            reachedAhead = ahead
            if (!linked && needsLink) {
                linkMs = connectedFor
                sinkWaitMs = link.sinkWaitMs
                if (ahead != null) volumeControl?.refresh()
            }
            try {
                block()
            } finally {
                reachedAhead = null
            }
        }
    }

    fun relabel(now: Target) {
        if (now.label == target.label) return
        Diag.log("targets", "${target.id}: now called \"${now.label}\" (was \"${target.label}\")")
        target = now
    }

    suspend fun keepAwake() {
        link.look()
        val checked = keeper?.beat(playing) ?: return
        if (checked && keeper.state == KeepAwake.KEPT) volumeControl?.refresh()
    }

    suspend fun tick() = lock.withLock {
        if (playing) {
            volumeControl?.refresh()
            reportProgress(finished = false)
            captureThePlace()
            save()
        }
    }

    suspend fun watch() {
        if (!lock.tryLock()) return
        try {
            watchLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun watchLocked() {
        if (!playing || starting) {
            lastSeenMs = null
            return
        }
        val position = player.positionMs
        val now = clock()
        if (position == null || position != lastSeenMs) {
            lastSeenMs = position
            movedAtMs = now
            if (problem?.trouble?.kind == TroubleKind.STALLED) {
                Diag.log("targets", "${target.id}: moving again at ${position}ms, the stall is over")
                problem = null
            }
            return
        }
        if (now - movedAtMs >= STALL_MS && problem == null) {
            val entry = queue.current
            val trouble = TargetTrouble(TroubleKind.STALLED, entry?.itemId())
            problem = TroubleWords.problem(target.label, trouble, entry?.item?.item?.title)
            Diag.warn(
                "targets",
                "${target.id}: no progress for ${now - movedAtMs}ms while playing ${entry?.item?.item?.title} " +
                    "(stuck at ${position}ms)",
            )
        }
    }

    suspend fun lost(reason: String) = lock.withLock {
        if (!playing && destination == null) {
            Diag.log("targets", "${target.id}: connection lost ($reason) with nothing playing, ignored")
            return@withLock
        }
        val entry = queue.current
        captureThePlace()
        Diag.warn(
            "targets",
            "${target.id}: connection lost ($reason) while ${if (playing) "playing" else "linked"} " +
                "${entry?.item?.item?.title}; stopping and keeping the place at ${placeMs}ms",
        )
        playing = false
        starting = false
        problem = TroubleWords.problem(
            target.label,
            TargetTrouble(TroubleKind.SWITCHED_OFF, entry?.itemId()),
            entry?.item?.item?.title,
            target.kind,
        )
        player.stop()
        destination = null
        sleep.release("connection lost: $reason")
        save()
    }

    private fun captureThePlace() {
        val current = queue.current ?: return
        if (isLive() || current.item.item.contentKind == MediaContentKind.LIVE) return
        player.positionMs?.let { placeMs = it }
    }

    private suspend fun moveTo(index: Int) {
        problem = null
        if (playing) reportProgress(finished = false)
        placeMs = 0
        startAt(index)
    }

    private suspend fun resume() {
        if (playing) return
        if (queue.current != null && destination != null) {
            player.pause(false)
            playing = true
            lastSeenMs = null
        } else {
            val index = queue.place.coerceAtLeast(0)
            val live = queue.entries.getOrNull(index)?.item?.item?.contentKind == MediaContentKind.LIVE
            Diag.log(
                "targets",
                "${target.id}: play from the saved place ${if (live) "(live, at the edge)" else "${placeMs}ms"} " +
                    "of item $index",
            )
            startAt(index, if (live) 0 else placeMs)
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
            val reached = destination ?: reachedAhead
            if (reached == null) {
                queue = queue.copy(currentIndex = at)
                problem = unreachableProblem()
                playing = false
                Diag.warn(
                    "targets",
                    "${target.id}: target unreachable, holding at $at before looking anything up: ${problem?.text}"
                )
                save()
                return
            }
            destination = reached
            val item = queue.entries[at].item
            val picture = target.kind.hasPicture && item.picture != Picture.NO
            val itemSound = sound.copy(skipSilence = item.item.contentKind.skipsSilence(sound.skipSilence))
            val lookedUpAt = clock()
            val stream = sounds.streamFor(item, picture)
            resolveMs += clock() - lookedUpAt
            if (stream == null) {
                val why = sounds.whyNone(item)
                val until = sounds.refusedUntil(item)
                val kind = if (until != null) TroubleKind.YOUTUBE_BACKOFF else TroubleKind.COULD_NOT_PLAY
                problem = TroubleWords.problem(
                    target.label,
                    TargetTrouble(kind, item.item.id.value, why, until),
                    item.item.title,
                )
                Diag.warn(
                    "targets",
                    "${target.id}: no stream for ${item.item.id.value} (${item.item.title}) " +
                        "picture=$picture, passing over: $why"
                )
                at++
                continue
            }
            queue = queue.copy(currentIndex = at)
            placeMs = if (item.item.contentKind == MediaContentKind.LIVE) 0 else startAtMs
            player.play(
                PlayRequest(stream.url, reached, itemSound, startAtMs, picture, item.item.title, stream.cast)
            )
            playing = true
            lastSeenMs = null
            movedAtMs = clock()
            startedAtMs = clock()
            Diag.log(
                "targets",
                "${target.id}: playing $at/${queue.entries.size} ${item.item.title} on $reached " +
                    "picture=$picture sound=$itemSound (asked for $sound, kind ${item.item.contentKind}) " +
                    "from=${startAtMs}ms volume=${volumeNow() ?: "unknown"} mode=${volumeControl?.mode ?: "n/a"} " +
                    "(a play never sets it)",
            )
            save()
            queue.entries.getOrNull(at + 1)?.let { lookAhead(it.item) }
            if (sleep.leftItems(item.item.id.value)) {
                Diag.log("targets", "${target.id}: moved off the items the sleep timer waited on, pausing")
                pausePlaying()
                sleep.release("playback moved off the items the timer waited on")
                save()
            }
            return
        }
        finish("reached the end", report = false, keepPlace = false)
    }

    private suspend fun reportProgress(finished: Boolean) {
        val index = queue.currentIndex
        val entry = state().entries.getOrNull(index) ?: return
        val duration = if (isLive()) null else player.durationMs ?: entry.item.durationMs
        val position = if (finished && duration != null) duration else (player.positionMs ?: 0)
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
        if (wasCurrent) placeMs = 0
        if (wasCurrent && playing) startAt(index)
    }

    private suspend fun removeIds(ids: List<String>) {
        val gone = ids.mapTo(HashSet()) { MediaItemId(it) }
        val wasCurrent = queue.current?.item?.item?.id in gone
        val keptBefore = queue.entries.take(queue.currentIndex.coerceAtLeast(0)).count { it.item.item.id !in gone }
        val before = queue.entries.size
        queue = queue.removingAll(gone)
        Diag.log("targets", "${target.id}: removed ${before - queue.entries.size} of ${ids.size} named item(s)")
        if (wasCurrent) placeMs = 0
        if (wasCurrent && playing) startAt(keptBefore)
    }

    private suspend fun finish(why: String, report: Boolean = true, keepPlace: Boolean = true) {
        val wasLinked = destination != null
        if (report && playing) reportProgress(finished = false)
        if (keepPlace) captureThePlace() else placeMs = 0
        player.stop()
        playing = false
        if (wasLinked) link.release()
        destination = null
        Diag.log("targets", "${target.id}: $why; target handed back=$wasLinked")
        sleep.release("playback stopped: $why")
        save()
    }

    private fun save() = store.save(
        state().copy(
            playing = false,
            problem = null,
            volume = null,
            volumeMode = null,
            keepAwake = null,
            live = false,
            power = TargetPower.ON,
            switchedOnByHand = false,
            starting = false,
            trouble = null,
            sleep = null,
        ),
        target.id
    )

    private fun restore() {
        val saved = store.restore(target.id) ?: return
        queue = saved.entries.toSnapshot(saved.at)
        sound = saved.sound
        repeat = saved.repeat.takeIf { it.onATarget } ?: RepeatMode.OFF
        provenance.restore(saved.entries)
        placeMs = saved.positionMs.takeIf { queue.current != null } ?: 0
        Diag.log("targets", "${target.id}: restored ${queue.entries.size} item(s), paused at ${queue.currentIndex}")
    }
}

private const val MAX_VOLUME = 100

private const val MIN_REPEAT_PLAY_MS = 2_000L

private const val STALL_MS = 20_000L

private fun QueueEntry.itemId(): String = item.item.id.value

private class PendingStart(val index: Int, val startAtMs: Long)

private val INDEXED_ACTIONS = setOf(TargetAction.JUMP, TargetAction.REMOVE)

private val STARTING_ACTIONS =
    setOf(TargetAction.PLAY, TargetAction.NEXT, TargetAction.PREVIOUS, TargetAction.JUMP, TargetAction.WAKE)

private fun TargetQueueRequest.playables(random: Random, targetId: String): List<PlayableItem> = items
    .mapNotNull { backup ->
        backup.toPlayable().also {
            if (it == null) Diag.warn("targets", "$targetId: ${backup.itemId} not playable, left out")
        }
    }
    .let { if (shuffle) it.shuffled(random) else it }

internal class StaleEdit(val state: TargetState) : RuntimeException("the queue changed under that edit")

internal sealed interface EnqueueResult {
    data class Accepted(val state: TargetState) : EnqueueResult
    data class Refused(val why: String) : EnqueueResult
}

internal class Provenances {
    private class Who(val addedBy: String?, val reports: Boolean, val loop: Boolean)

    private val byItem = HashMap<String, Who>()

    fun note(items: List<PlayableItem>, from: Requester?, ownerId: String?, loop: Boolean) {
        items.forEach { byItem[it.item.id.value] = Who(from?.label, TargetReporting.reports(from, ownerId), loop) }
    }

    fun restore(entries: List<TargetEntry>) {
        entries.forEach { byItem[it.item.itemId] = Who(it.addedBy, it.reportsToYouTube, it.loop) }
    }

    fun loops(itemId: String): Boolean = byItem[itemId]?.loop == true

    fun stamp(entry: TargetEntry): TargetEntry {
        val who = byItem[entry.item.itemId]
        return entry.copy(addedBy = who?.addedBy, reportsToYouTube = who?.reports == true, loop = who?.loop == true)
    }
}
