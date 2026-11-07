package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.IOException
import kotlin.math.roundToInt

internal class ProcessedAudio(
    private val engine: AudioEngine,
    private val label: String,
    cuts: Boolean,
) {
    val timeline = CastTimeline(cuts = cuts) { durationUs }
    private var silence: ByteArray? = null
    private var lastReal = Int.MAX_VALUE
    private val lock = Object()
    private val segments = HashMap<Int, ByteArray>()
    private var producer: Thread? = null
    private var producingFrom = -1
    private var produced = -1
    private var wantedUpTo = 0
    private var closed = false
    private var failure: Throwable? = null

    private val earlyEnds = EarlyEnds()

    @Volatile
    private var generation: Any? = null

    lateinit var format: AacFormat
        private set
    var durationUs: Long = UNKNOWN_US
        private set
    var framesPerSegment: Int = 0
        private set

    val segmentSamples: Long get() = framesPerSegment.toLong() * Fmp4Aac.SAMPLES_PER_FRAME

    val summary: String get() = engine.summary

    fun prepare() {
        val probed = engine.probe()
        val rate = probed.sampleRate
        val channels = probed.channels.coerceIn(1, 2)
        durationUs = probed.durationUs
        format = AacFormat(rate, channels, aacLcConfig(rate, channels))
        framesPerSegment = (SEGMENT_SECONDS * rate / Fmp4Aac.SAMPLES_PER_FRAME).roundToInt()
        Diag.log(
            "cast",
            "audio $label prepared: ${rate}Hz ${channels}ch " +
                "duration=${durationUs / MILLI}ms segment=${segmentSamples * MILLI / rate}ms",
        )
    }

    fun init(): ByteArray = Fmp4Aac.init(format)

    fun segment(n: Int): ByteArray = synchronized(lock) {
        wantedUpTo = maxOf(wantedUpTo, n + AHEAD)
        segments[n]?.let { return it }
        if (n > lastReal) return silentSegment(n)
        val running = producer?.isAlive == true
        if (!running || n !in producingFrom..produced + RESTART_GAP || n <= produced) {
            Diag.log(
                "cast",
                "audio $label segment $n asked; producing from it " +
                    "(was from=$producingFrom produced=$produced running=$running)",
            )
            start(n)
        }
        lock.notifyAll()
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (segments[n] == null) {
            failure?.let { throw IllegalStateException("audio $label failed: ${it.message}", it) }
            val left = deadline - System.currentTimeMillis()
            check(left > 0) { "audio $label segment $n not ready in ${WAIT_MS}ms (produced=$produced)" }
            lock.wait(left)
        }
        segments.getValue(n)
    }

    fun close() {
        synchronized(lock) {
            closed = true
            generation = null
            producer?.interrupt()
            lock.notifyAll()
        }
        engine.close()
    }

    private fun start(n: Int) {
        producer?.interrupt()
        failure = null
        producingFrom = n
        produced = n - 1
        lastReal = Int.MAX_VALUE
        segments.keys.retainAll { it in n - KEEP_BEHIND..n + AHEAD }
        wantedUpTo = n + AHEAD
        val mine = Any()
        generation = mine
        val tvUs = n * segmentSamples * MICROS / format.sampleRate
        val sourceUs = timeline.sourceFor(tvUs)
        timeline.anchor(tvUs, sourceUs)
        if (timeline.cutsSilences) {
            Diag.log(
                "cast",
                "audio $label segment $n is ${tvUs / MILLI}ms on the TV, ${sourceUs / MILLI}ms in the source"
            )
        }
        producer = Thread({ produce(n, sourceUs, mine) }, "cast-audio-$label").apply { start() }
    }

    private fun sinkFor(mine: Any) = object : TranscodeSink {
        override fun stale() = generation !== mine || closed

        override fun waitWhileAhead(segment: Int) = synchronized(lock) {
            while (segment > wantedUpTo && !stale()) lock.wait(IDLE_WAIT_MS)
        }

        override fun publish(segment: Int, bytes: ByteArray, tvEndUs: Long, sourceEndUs: Long) = synchronized(lock) {
            if (stale()) return@synchronized
            timeline.anchor(tvEndUs, sourceEndUs)
            segments[segment] = bytes
            produced = segment
            segments.keys.retainAll { it >= wantedUpTo - AHEAD - KEEP_BEHIND }
            lock.notifyAll()
        }

        override fun ended(tvEndUs: Long) = synchronized(lock) {
            if (stale()) return@synchronized
            lastReal = produced
            timeline.ended(tvEndUs)
            lock.notifyAll()
        }
    }

    private fun silentSegment(n: Int): ByteArray {
        val frame = silence ?: engine.silentFrame(format).also { silence = it }
        Diag.log("cast", "audio $label segment $n is past the real end (segment $lastReal); serving silence")
        return Fmp4Aac.segment(n + 1, n * segmentSamples, List(framesPerSegment) { frame })
    }

    private fun produce(first: Int, sourceUs: Long, mine: Any) {
        val started = System.currentTimeMillis()
        val sink = sinkFor(mine)
        try {
            val plan = TranscodePlan(format, framesPerSegment, first, label, sourceUs, durationUs, trustedEnd())
            engine.transcode(plan, sink)
        } catch (e: InterruptedException) {
            Diag.log("cast", "audio $label producer from $first interrupted: ${e.message}")
        } catch (e: IOException) {
            fail(first, mine, e)
        } catch (e: IllegalStateException) {
            fail(first, mine, e)
        } catch (e: IllegalArgumentException) {
            fail(first, mine, e)
        }
        val end = if (sink.stale()) "stopped" else "end of source"
        Diag.log(
            "cast",
            "audio $label produced segments $first..${synchronized(lock) { produced }} " +
                "in ${System.currentTimeMillis() - started}ms ($end)",
        )
    }

    private fun trustedEnd(): Long? {
        val trusted = earlyEnds.trusted(System.currentTimeMillis())
        if (trusted == null && earlyEnds.describe != "none") {
            Diag.log("cast", "audio $label: an early end (${earlyEnds.describe}) is not believed yet; reading on")
        }
        return trusted
    }

    private fun fail(first: Int, mine: Any, e: Exception) {
        Diag.warn("cast", "audio $label producer from $first failed", e)
        if (e is EarlyEnd) earlyEnds.seen(e.atUs, System.currentTimeMillis())
        synchronized(lock) {
            if (generation === mine) failure = e
            lock.notifyAll()
        }
    }

    private fun aacLcConfig(rate: Int, channels: Int): ByteArray {
        val index = RATES.indexOf(rate)
        check(index >= 0) { "AAC cannot carry ${rate}Hz" }
        val v = (AAC_LC shl AOT_SHIFT) or (index shl RATE_SHIFT) or (channels shl CHANNEL_SHIFT)
        return byteArrayOf((v shr BYTE).toByte(), v.toByte())
    }

    private companion object {
        const val SEGMENT_SECONDS = 4.0
        const val AHEAD = 4
        const val KEEP_BEHIND = 3
        const val RESTART_GAP = 3
        const val WAIT_MS = 45_000L
        const val IDLE_WAIT_MS = 1_000L
        const val MILLI = 1000
        const val MICROS = 1_000_000L
        const val AAC_LC = 2
        const val AOT_SHIFT = 11
        const val RATE_SHIFT = 7
        const val CHANNEL_SHIFT = 3
        const val BYTE = 8
        val RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
