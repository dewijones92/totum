package com.dewijones92.totum.playback.cast

import android.content.Context
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.MediaExtractorCompat
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.ChunkedDataSource
import com.dewijones92.totum.playback.SilenceCuttingAudioProcessor
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.playback.totumExtractors
import java.io.IOException
import kotlin.math.roundToInt

@OptIn(markerClass = [UnstableApi::class])
@Suppress("TooManyFunctions")
internal class ProcessedAudio(
    private val context: Context,
    private val source: String,
    private val boost: () -> VolumeBoost,
    private val label: String,
    private val cutter: (() -> SilenceCuttingAudioProcessor)?,
) {
    val timeline = CastTimeline(cuts = cutter != null) { durationUs }
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
    private var spare: MediaExtractorCompat? = null
    private var spareOpenedAt = 0L

    @Volatile
    private var earlyEndUs: Long? = null
    private val refilling = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var generation: Any? = null

    lateinit var format: AacFormat
        private set
    var durationUs: Long = C.TIME_UNSET
        private set
    var framesPerSegment: Int = 0
        private set

    val segmentSamples: Long get() = framesPerSegment.toLong() * Fmp4Aac.SAMPLES_PER_FRAME

    fun prepare() {
        val extractor = extractor()
        var kept = false
        try {
            val f = extractor.getTrackFormat(audioTrack(extractor))
            val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            durationUs = f.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
                ?: C.TIME_UNSET
            format = AacFormat(rate, channels, aacLcConfig(rate, channels))
            framesPerSegment = (SEGMENT_SECONDS * rate / Fmp4Aac.SAMPLES_PER_FRAME).roundToInt()
            Diag.log(
                "cast",
                "audio $label prepared: ${f.getString(MediaFormat.KEY_MIME)} ${rate}Hz ${channels}ch " +
                    "duration=${durationUs / MILLI}ms segment=${segmentSamples * MILLI / rate}ms",
            )
            kept = true
        } finally {
            if (kept) keepSpare(extractor) else extractor.release()
        }
    }

    private fun keepSpare(extractor: MediaExtractorCompat) {
        val kept = synchronized(lock) {
            if (closed || spare != null) {
                false
            } else {
                spare = extractor
                spareOpenedAt = System.currentTimeMillis()
                true
            }
        }
        if (!kept) extractor.release()
    }

    private fun takeSpare(): MediaExtractorCompat? {
        val (taken, ageMs) = synchronized(lock) {
            val s = spare
            spare = null
            s to System.currentTimeMillis() - spareOpenedAt
        }
        if (taken == null || ageMs <= SPARE_FRESH_MS) return taken
        Diag.log("cast", "audio $label spare source is ${ageMs}ms old; opening a fresh one instead")
        taken.release()
        return null
    }

    private fun refillSpare() {
        if (!refilling.compareAndSet(false, true)) return
        Thread({
            try {
                keepSpare(extractor())
            } catch (e: IOException) {
                Diag.warn("cast", "audio $label could not open a spare source", e)
            } catch (e: IllegalStateException) {
                Diag.warn("cast", "audio $label could not open a spare source", e)
            } finally {
                refilling.set(false)
            }
        }, "cast-spare-$label").apply { isDaemon = true }.start()
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
        val idle = synchronized(lock) {
            closed = true
            generation = null
            producer?.interrupt()
            lock.notifyAll()
            spare.also { spare = null }
        }
        idle?.let { extractor -> Thread({ extractor.release() }, "cast-spare-release-$label").start() }
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
        val frame = silence ?: SilentAac.frame(format).also { silence = it }
        Diag.log("cast", "audio $label segment $n is past the real end (segment $lastReal); serving silence")
        return Fmp4Aac.segment(n + 1, n * segmentSamples, List(framesPerSegment) { frame })
    }

    private fun produce(first: Int, sourceUs: Long, mine: Any) {
        val started = System.currentTimeMillis()
        val sink = sinkFor(mine)
        var transcode: AudioTranscode? = null
        try {
            val spared = takeSpare()
            val extractor = spared ?: extractor()
            refillSpare()
            Diag.log(
                "cast",
                "audio $label from segment $first: source ${if (spared != null) "was waiting" else "opened"} " +
                    "in ${System.currentTimeMillis() - started}ms",
            )
            val plan = TranscodePlan(format, framesPerSegment, first, label, sourceUs, durationUs, earlyEndUs)
            try {
                AudioTranscode(plan, extractor, audioTrack(extractor), boost, sink, cutter?.invoke())
                    .also { transcode = it }
                    .run()
            } catch (e: IOException) {
                if (spared == null || synchronized(lock) { produced } >= first) throw e
                Diag.warn(
                    "cast",
                    "audio $label waiting source failed before segment $first; once more on a fresh one",
                    e,
                )
                val fresh = extractor()
                AudioTranscode(plan, fresh, audioTrack(fresh), boost, sink, cutter?.invoke())
                    .also { transcode = it }
                    .run()
            }
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
            "audio $label produced segments $first..${transcode?.lastSegment} " +
                "in ${System.currentTimeMillis() - started}ms ($end)",
        )
    }

    private fun fail(first: Int, mine: Any, e: Exception) {
        Diag.warn("cast", "audio $label producer from $first failed", e)
        if (e is EarlyEnd) earlyEndUs = e.atUs
        synchronized(lock) {
            if (generation === mine) failure = e
            lock.notifyAll()
        }
    }

    private fun extractor() = MediaExtractorCompat(
        totumExtractors(),
        ChunkedDataSource.Factory(DefaultDataSource.Factory(context)),
    ).apply { setDataSource(source.toUri(), 0) }

    private fun audioTrack(extractor: MediaExtractorCompat): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("no audio track in $label")

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
        const val SPARE_FRESH_MS = 20_000L
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
