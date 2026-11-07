package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.appendingAll
import com.dewijones92.totum.data.queue.insertingAllNext
import com.dewijones92.totum.data.queue.removingAt
import com.dewijones92.totum.data.speakers.DEFAULT_VOLUME
import com.dewijones92.totum.data.speakers.QueueMode
import com.dewijones92.totum.data.speakers.Speaker
import com.dewijones92.totum.data.speakers.SpeakerAction
import com.dewijones92.totum.data.speakers.SpeakerControl
import com.dewijones92.totum.data.speakers.SpeakerQueueRequest
import com.dewijones92.totum.data.speakers.SpeakerState
import com.dewijones92.totum.data.speakers.SpeakersJson
import com.dewijones92.totum.data.speakers.toSnapshot
import com.dewijones92.totum.data.speakers.toSpeakerEntries
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.random.Random

internal class SpeakerDeck(
    private val speaker: Speaker,
    private val player: SpeakerPlayer,
    private val link: SpeakerLink,
    private val sounds: SpeakerSounds,
    private val file: File,
    private val random: Random = Random.Default,
    private val lookAhead: (PlayableItem) -> Unit = {},
) {
    private val lock = Mutex()
    private var queue = QueueSnapshot()
    private var volume = DEFAULT_VOLUME
    private var playing = false
    private var sink: String? = null
    private var problem: String? = null

    init {
        restore()
    }

    fun state(): SpeakerState = SpeakerState(
        speaker = speaker,
        entries = queue.toSpeakerEntries(),
        at = queue.currentIndex,
        playing = playing,
        positionMs = (if (queue.current != null) player.positionMs else null) ?: 0,
        durationMs = if (queue.current != null) player.durationMs else null,
        volume = volume,
        linked = sink != null,
        problem = problem,
    )

    suspend fun enqueue(request: SpeakerQueueRequest): SpeakerState = lock.withLock {
        val wanted = request.items.mapNotNull { backup ->
            backup.toPlayable().also {
                if (it == null) Diag.warn("speakers", "${speaker.id}: ${backup.itemId} not playable, left out")
            }
        }.let { if (request.shuffle) it.shuffled(random) else it }
        if (wanted.isEmpty()) {
            Diag.log("speakers", "${speaker.id}: nothing playable in a ${request.mode} of ${request.items.size}")
            return@withLock state()
        }
        val group = request.group
        queue = when (request.mode) {
            QueueMode.REPLACE -> QueueSnapshot(wanted.map { QueueEntry(it, group) })
            QueueMode.NEXT -> queue.insertingAllNext(wanted, group)
            QueueMode.END -> queue.appendingAll(wanted, group)
        }
        Diag.log(
            "speakers",
            "${speaker.id}: ${request.mode} ${wanted.size} item(s) group=${group?.title} shuffle=${request.shuffle} " +
                "queue=${queue.entries.size} playing=$playing",
        )
        if (request.mode == QueueMode.REPLACE || !playing) {
            val first = queue.entries.indexOfFirst { it.item.item.id == wanted.first().item.id }
            startAt(first.coerceAtLeast(0))
        } else {
            save()
        }
        state()
    }

    suspend fun control(control: SpeakerControl): SpeakerState = lock.withLock {
        Diag.log(
            "speakers",
            "${speaker.id}: ${control.action} index=${control.index} at=${queue.currentIndex} playing=$playing"
        )
        when (control.action) {
            SpeakerAction.PLAY -> resume()
            SpeakerAction.PAUSE -> if (playing) {
                player.pause(true)
                playing = false
            }
            SpeakerAction.NEXT -> startAt(queue.place + 1)
            SpeakerAction.PREVIOUS -> startAt((queue.place - 1).coerceAtLeast(0))
            SpeakerAction.JUMP -> control.index?.let { startAt(it) }
            SpeakerAction.SEEK -> control.positionMs?.let(player::seek)
            SpeakerAction.VOLUME -> control.volume?.let { setVolume(it) }
            SpeakerAction.STOP -> finish("stopped")
            SpeakerAction.REMOVE -> control.index?.let { remove(it) }
        }
        save()
        state()
    }

    suspend fun ended(failed: Boolean) = lock.withLock {
        if (!playing) {
            Diag.log("speakers", "${speaker.id}: an end with nothing playing, ignored (failed=$failed)")
            return@withLock
        }
        Diag.log(
            "speakers",
            "${speaker.id}: ${queue.current?.item?.item?.title} ${if (failed) "failed" else "finished"}, moving on"
        )
        startAt(queue.currentIndex + 1)
    }

    private suspend fun resume() {
        if (playing) return
        if (queue.current != null && sink != null) {
            player.pause(false)
            playing = true
        } else {
            startAt(queue.place.coerceAtLeast(0))
        }
    }

    private suspend fun startAt(index: Int) {
        var at = index
        while (at in queue.entries.indices) {
            val item = queue.entries[at].item
            val url = sounds.urlFor(item)
            if (url == null) {
                Diag.warn(
                    "speakers",
                    "${speaker.id}: no sound for ${item.item.id.value} (${item.item.title}), passing over"
                )
                at++
                continue
            }
            val target = sink ?: link.connect().also { sink = it }
            queue = queue.copy(currentIndex = at)
            if (target == null) {
                problem = "⚠️ Couldn't reach ${speaker.label}"
                playing = false
                Diag.warn("speakers", "${speaker.id}: speaker unreachable, holding at $at")
                save()
                return
            }
            player.play(url, target, volume)
            playing = true
            problem = null
            Diag.log("speakers", "${speaker.id}: playing $at/${queue.entries.size} ${item.item.title} on $target")
            save()
            queue.entries.getOrNull(at + 1)?.let { lookAhead(it.item) }
            return
        }
        finish("reached the end")
    }

    private fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, MAX_VOLUME)
        player.volume(volume)
    }

    private suspend fun remove(index: Int) {
        if (index !in queue.entries.indices) return
        val wasCurrent = index == queue.currentIndex
        queue = queue.removingAt(index)
        if (wasCurrent && playing) startAt(index)
    }

    private suspend fun finish(why: String) {
        val wasLinked = sink != null
        player.stop()
        playing = false
        if (wasLinked) link.release()
        sink = null
        Diag.log("speakers", "${speaker.id}: $why; speaker handed back=$wasLinked")
        save()
    }

    private fun save() {
        runCatching {
            file.writeTextReplacing(
                SpeakersJson.encodeToString(SpeakerState.serializer(), state().copy(playing = false, problem = null))
            )
        }.onFailure { Diag.warn("speakers", "${speaker.id}: queue not saved: $it") }
    }

    private fun restore() {
        if (!file.exists()) return
        runCatching { SpeakersJson.decodeFromString(SpeakerState.serializer(), file.readText()) }
            .onSuccess {
                queue = it.entries.toSnapshot(it.at)
                volume = it.volume
                Diag.log(
                    "speakers",
                    "${speaker.id}: restored ${queue.entries.size} item(s), paused at ${queue.currentIndex}"
                )
            }
            .onFailure { Diag.warn("speakers", "${speaker.id}: saved queue unreadable, starting empty: $it") }
    }
}

private const val MAX_VOLUME = 100
