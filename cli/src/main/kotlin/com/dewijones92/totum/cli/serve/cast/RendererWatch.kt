package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import java.io.IOException
import kotlin.concurrent.thread

internal enum class RendererState { PLAYING, PAUSED, OVER, OTHER }

internal class RendererSnapshot(
    val state: RendererState,
    val positionMs: Long,
    val durationMs: Long?,
    val errored: Boolean = false,
    val detail: String = "",
)

internal class RendererWatch(
    private val label: String,
    private val clock: () -> Long,
    private val pollMs: Long,
    private val polls: Boolean,
    private val look: () -> RendererSnapshot,
    private val seekNow: (Long) -> Unit,
    private val extras: () -> Unit = {},
) {
    var onEnded: (failed: Boolean) -> Unit = {}

    var onLost: (reason: String) -> Unit = {}

    private val lock = Any()

    @Volatile private var latest: CastReading? = null

    @Volatile private var loaded = false

    @Volatile private var loadedAt = 0L

    @Volatile private var sawPlaying = false

    @Volatile private var pendingSeekMs = 0L

    @Volatile private var generation = 0

    @Volatile private var misses = 0

    @Volatile private var polling: Thread? = null

    val reading: CastReading? get() = latest

    val isLoaded: Boolean get() = loaded

    fun beforeLoad() = synchronized(lock) {
        generation++
        loaded = false
        sawPlaying = false
        latest = null
        misses = 0
    }

    fun loaded(startAtMs: Long) {
        synchronized(lock) {
            pendingSeekMs = startAtMs
            loadedAt = clock()
            loaded = true
        }
        startPolling()
    }

    fun refused() {
        onEnded(true)
    }

    fun stopped(): Boolean {
        val was = synchronized(lock) {
            generation++
            loaded.also { loaded = false }
        }
        latest = null
        return was
    }

    fun moved(positionMs: Long) {
        latest = latest?.let { CastReading(positionMs, it.durationMs, it.playing, clock()) }
    }

    private fun startPolling() {
        if (!polls) return
        synchronized(lock) {
            if (polling != null) return
            polling = thread(name = "renderer-poll-$label", isDaemon = true) { pollWhileWanted() }
        }
    }

    private fun pollWhileWanted() {
        var tick = 0
        var idle = 0
        while (true) {
            Thread.sleep(pollMs)
            if (!loaded) {
                synchronized(lock) {
                    if (++idle >= IDLE_TICKS && !loaded) {
                        polling = null
                        return
                    }
                }
                continue
            }
            idle = 0
            poll(extrasToo = tick++ % EXTRAS_EVERY == 0)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun poll(extrasToo: Boolean = false) {
        val mine = generation
        if (!loaded) return
        val seen = try {
            look()
        } catch (e: IOException) {
            missed(mine, e)
            return
        }
        if (extrasToo) extras()
        var verdict: Boolean? = null
        synchronized(lock) {
            if (generation != mine || !loaded) return
            misses = 0
            val playing = seen.state == RendererState.PLAYING
            latest = CastReading(seen.positionMs, seen.durationMs, playing, clock())
            if (playing) sawPlaying = true
            if ((playing || seen.state == RendererState.PAUSED) && pendingSeekMs > 0) {
                val wanted = pendingSeekMs
                pendingSeekMs = 0
                seekNow(wanted)
            }
            if (seen.state == RendererState.OVER) {
                val waited = clock() - loadedAt
                if (sawPlaying || seen.errored || waited > START_TIMEOUT_MS) {
                    Diag.log(
                        "cast-target",
                        "$label: renderer is ${seen.detail.ifEmpty { "over" }} after ${seen.positionMs}ms, " +
                            "seen playing=$sawPlaying, waited ${waited}ms; the item is over, " +
                            "failed=${seen.errored || !sawPlaying}",
                    )
                    loaded = false
                    verdict = seen.errored || !sawPlaying
                }
            }
        }
        verdict?.let(onEnded)
    }

    private fun missed(mine: Int, e: IOException) {
        var lost = false
        synchronized(lock) {
            if (generation != mine || !loaded) return
            misses++
            Diag.warn("cast-target", "$label: poll $misses of $LOST_AFTER failed: ${e.message}")
            if (misses >= LOST_AFTER) {
                loaded = false
                lost = true
            }
        }
        if (lost) onLost("$label stopped answering")
    }

    private companion object {
        const val EXTRAS_EVERY = 10
        const val IDLE_TICKS = 30
        const val LOST_AFTER = 3
        const val START_TIMEOUT_MS = 20_000L
    }
}
