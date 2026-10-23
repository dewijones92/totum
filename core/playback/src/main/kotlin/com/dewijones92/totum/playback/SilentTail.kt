package com.dewijones92.totum.playback

import kotlin.math.sqrt

internal class SilentRun(private val sampleRate: Int, private val channels: Int) {
    private val blockFrames = (sampleRate / BLOCKS_PER_SECOND).coerceAtLeast(1)
    private var blockSquares = 0.0
    private var blockCount = 0
    private var silentBlocks = 0L

    @Volatile
    var silentForMs: Long = 0
        private set

    fun add(samples: ShortArray, count: Int) {
        var i = 0
        while (i + channels <= count) {
            var frame = 0.0
            for (c in 0 until channels) {
                val s = samples[i + c].toDouble()
                frame += s * s
            }
            blockSquares += frame / channels
            blockCount++
            i += channels
            if (blockCount == blockFrames) endBlock()
        }
    }

    fun reset() {
        blockSquares = 0.0
        blockCount = 0
        silentBlocks = 0
        silentForMs = 0
    }

    private fun endBlock() {
        val rms = sqrt(blockSquares / blockCount)
        silentBlocks = if (rms < SILENT_RMS) silentBlocks + 1 else 0
        silentForMs = silentBlocks * MS_PER_SECOND / BLOCKS_PER_SECOND
        blockSquares = 0.0
        blockCount = 0
    }

    private companion object {
        const val BLOCKS_PER_SECOND = 100
        const val MS_PER_SECOND = 1_000L
        const val SILENT_RMS = 60.0
    }
}

internal fun tailWindowMs(durationMs: Long): Long = minOf(LONGEST_TAIL_MS, durationMs / TAIL_SHARE)

internal fun endsOnSilentTail(positionMs: Long, durationMs: Long?, silentForMs: Long): Boolean {
    val duration = durationMs?.takeIf { it > 0 } ?: return false
    val left = duration - positionMs
    return left in 1..tailWindowMs(duration) && silentForMs >= SILENT_FOR_MS
}

internal const val SILENT_FOR_MS = 5_000L
private const val LONGEST_TAIL_MS = 60_000L
private const val TAIL_SHARE = 10

internal class SilentTailWatch(
    private val player: () -> androidx.media3.common.Player?,
    private val silentForMs: () -> Long,
    private val speech: TailSpeechScans? = null,
) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var noted: String? = null
    private var acted: String? = null

    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field != value) com.dewijones92.totum.common.Diag.log("silence", "silent tail ends the item: $value")
            field = value
        }

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, TAIL_TICK_MS)
        }
    }

    fun start() {
        handler.postDelayed(tick, TAIL_TICK_MS)
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    private fun check() {
        val player = player()?.takeIf { it.isPlaying } ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val position = player.currentPosition
        if (enabled && endNoSpeechLeft(player, id, position, duration)) return
        if (duration - position > tailWindowMs(duration)) {
            if (noted == id) noted = null
            if (acted == id) acted = null
            return
        }
        if (noted != id) {
            noted = id
            com.dewijones92.totum.common.Diag.log(
                "silence",
                "$id is in its last ${tailWindowMs(duration)}ms: " +
                    "${if (enabled) "listening for" else "not acting on"} " +
                    "a silent tail (silent for ${silentForMs()}ms now)",
            )
        }
        if (!enabled || acted == id || !endsOnSilentTail(position, duration, silentForMs())) return
        acted = id
        com.dewijones92.totum.common.Diag.log(
            "silence",
            "$id went silent for ${silentForMs()}ms at ${position}ms of ${duration}ms: " +
                "ending it, as if it had finished",
        )
        player.seekTo(duration)
    }

    private fun endNoSpeechLeft(
        player: androidx.media3.common.Player,
        id: String,
        position: Long,
        duration: Long,
    ): Boolean {
        val scans = speech ?: return false
        scans.want(id, player.currentMediaItem?.localConfiguration?.uri?.toString(), duration)
        val lastWords = scans.speechEnds(id)
        if (acted == id || !endsWithNoSpeechLeft(position, duration, lastWords)) return false
        acted = id
        com.dewijones92.totum.common.Diag.log(
            "silence",
            "$id has no speech after ${lastWords}ms (its file was scanned ahead); ending it at ${position}ms of " +
                "${duration}ms, as if it had finished",
        )
        player.seekTo(duration)
        return true
    }
}

private const val TAIL_TICK_MS = 500L
