package com.dewijones92.totum.playback.cast

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.MediaExtractorCompat
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.BoostingAudioProcessor
import com.dewijones92.totum.playback.ChunkedDataSource
import com.dewijones92.totum.playback.SilenceCuttingAudioProcessor
import com.dewijones92.totum.playback.SpeechGate
import com.dewijones92.totum.playback.TrackSummary
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.playback.homeServerAware
import com.dewijones92.totum.playback.totumExtractors
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(markerClass = [UnstableApi::class])
internal class MediaCodecAudioEngine(
    private val context: Context,
    private val source: String,
    private val label: String,
    private val boost: () -> VolumeBoost,
    private val cutter: (() -> SilenceCuttingAudioProcessor)?,
    private val newBooster: () -> BoostingAudioProcessor = { BoostingAudioProcessor() },
    private val gate: () -> SpeechGate = { SpeechGate.OFF },
) : AudioEngine {
    private val lock = Object()
    private var closed = false
    private var spare: MediaExtractorCompat? = null
    private var spareOpenedAt = 0L
    private val refilling = AtomicBoolean(false)

    @Volatile
    private var format: AacFormat? = null

    override val summary: String get() = "boost=${boost()}"

    override fun probe(): AudioProbe {
        val extractor = extractor()
        var kept = false
        try {
            val f = extractor.getTrackFormat(audioTrack(extractor))
            val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            val durationUs = f.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
                ?: C.TIME_UNSET
            Diag.log(
                "cast",
                "audio $label source: ${f.getString(MediaFormat.KEY_MIME)} ${rate}Hz ${channels}ch",
            )
            kept = true
            return AudioProbe(rate, channels, durationUs)
        } finally {
            if (kept) keepSpare(extractor) else extractor.release()
        }
    }

    override fun silentFrame(format: AacFormat): ByteArray = SilentAac.frame(format)

    override fun transcode(plan: TranscodePlan, sink: TranscodeSink) {
        val started = System.currentTimeMillis()
        val spared = takeSpare()
        val extractor = spared ?: extractor()
        refillSpare()
        Diag.log(
            "cast",
            "audio $label from segment ${plan.first}: source ${if (spared != null) "was waiting" else "opened"} " +
                "in ${System.currentTimeMillis() - started}ms",
        )
        val published = AtomicBoolean(false)
        val watched = object : TranscodeSink by sink {
            override fun publish(segment: Int, bytes: ByteArray, tvEndUs: Long, sourceEndUs: Long) {
                published.set(true)
                sink.publish(segment, bytes, tvEndUs, sourceEndUs)
            }
        }
        try {
            run(plan, extractor, watched)
        } catch (e: IOException) {
            if (spared == null || published.get()) throw e
            Diag.warn(
                "cast",
                "audio $label waiting source failed before segment ${plan.first}; once more on a fresh one",
                e,
            )
            run(plan, extractor(), watched)
        }
    }

    private fun run(plan: TranscodePlan, extractor: MediaExtractorCompat, sink: TranscodeSink) {
        AudioTranscode(
            plan,
            extractor,
            audioTrack(extractor),
            boost,
            sink,
            cutter?.invoke(),
            newBooster(),
            gate,
        ).run()
    }

    override fun close() {
        val idle = synchronized(lock) {
            closed = true
            spare.also { spare = null }
        }
        idle?.let { extractor -> Thread({ extractor.release() }, "cast-spare-release-$label").start() }
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

    private fun extractor() = castExtractor(context, source.toUri())

    private fun audioTrack(extractor: MediaExtractorCompat): Int {
        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        val found = mimes.indexOfFirst { it?.startsWith("audio/") == true }
        if (found >= 0) return found
        val summary = TrackSummary.of(mimes, seekable = null)
        Diag.warn("cast", "audio $label: no audio track to cast: $summary")
        if (mimes.isEmpty()) throw NoReadableTracks(label, summary)
        error("no audio track in $label ($summary)")
    }

    private companion object {
        const val SPARE_FRESH_MS = 20_000L
    }
}

@UnstableApi
internal fun castExtractor(context: Context, uri: Uri): MediaExtractorCompat = MediaExtractorCompat(
    totumExtractors(),
    ChunkedDataSource.Factory(homeServerAware(DefaultDataSource.Factory(context))),
).apply { setDataSource(uri, 0) }
