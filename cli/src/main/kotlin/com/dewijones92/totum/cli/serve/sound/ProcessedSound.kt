package com.dewijones92.totum.cli.serve.sound

import com.dewijones92.totum.cli.serve.targets.withoutApiKeys
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.PcmChain
import com.dewijones92.totum.playback.SpeechWeights
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal const val SOUND_RATE = 48_000
internal const val SOUND_CHANNELS = 2
private const val BYTES_PER_SAMPLE = 2
private const val MS_PER_S = 1_000L
private const val CHUNK_FRAMES = 4_096
private const val STOP_WAIT_S = 2L
private const val PROBE_WAIT_S = 20L
private const val MICROS = 1_000_000L

internal class PcmPump(private val chain: PcmChain, private val channels: Int, private val rate: Int) {

    val skippedFrames: Long get() = chain.skippedFrames

    val skippedMs: Long get() = skippedFrames * MS_PER_S / rate

    fun run(input: InputStream, output: OutputStream, stillWanted: () -> Boolean = { true }): Long {
        val frameBytes = channels * BYTES_PER_SAMPLE
        val buffer = ByteArray(CHUNK_FRAMES * frameBytes)
        var held = 0
        var written = 0L
        var open = true
        while (open && stillWanted()) {
            val read = input.read(buffer, held, buffer.size - held)
            open = read >= 0
            if (open) held += read
            val whole = held / frameBytes * frameBytes
            if (open && whole > 0) {
                written += write(output, chain.process(samplesOf(buffer, whole)))
                buffer.copyInto(buffer, 0, whole, held)
                held -= whole
            }
        }
        if (stillWanted()) written += write(output, chain.finish())
        output.flush()
        return written
    }

    private fun write(output: OutputStream, samples: ShortArray): Long {
        if (samples.isEmpty()) return 0
        val bytes = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        output.write(bytes.array())
        return bytes.capacity().toLong()
    }

    private fun samplesOf(buffer: ByteArray, count: Int): ShortArray {
        val samples = ShortArray(count / BYTES_PER_SAMPLE)
        ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }
}

internal class Pipeline(
    val output: InputStream?,
    private val processes: List<Process>,
    private val pump: PcmPump,
    private val label: String,
) {
    @Volatile private var stopped = false

    val skippedMs: Long get() = pump.skippedMs

    val skippedFrames: Long get() = pump.skippedFrames

    fun stop() {
        if (stopped) return
        stopped = true
        processes.forEach { it.destroy() }
        processes.forEach { if (!it.waitFor(STOP_WAIT_S, TimeUnit.SECONDS)) it.destroyForcibly() }
        Diag.log("sound", "$label: stopped, ${pump.skippedMs}ms of silence had been skipped")
    }

    val wanted: Boolean get() = !stopped
}

internal class ProcessedSound(
    private val ffmpeg: String = System.getenv("TOTUM_FFMPEG") ?: "ffmpeg",
    private val ffprobe: String = System.getenv("TOTUM_FFPROBE") ?: "ffprobe",
    private val weights: () -> SpeechWeights? = ::bundledWeights,
) {

    fun chainFor(sound: SoundSettings): PcmChain = PcmChain(
        SOUND_RATE,
        SOUND_CHANNELS,
        sound.skipSilence,
        sound.boost,
        if (sound.skipSilence) weights() else null,
    )

    fun decodeCommand(url: String, startAtMs: Long): List<String> = buildList {
        add(ffmpeg)
        addAll(listOf("-v", "error", "-nostdin"))
        if (startAtMs > 0) addAll(listOf("-ss", "%.3f".format(startAtMs / MS_PER_S.toDouble())))
        addAll(listOf("-i", url, "-vn", "-ac", "$SOUND_CHANNELS", "-ar", "$SOUND_RATE", "-f", "s16le", "-"))
    }

    fun encodeCommand(): List<String> = listOf(
        ffmpeg, "-v", "error", "-nostdin", "-f", "s16le", "-ar", "$SOUND_RATE", "-ac", "$SOUND_CHANNELS",
        "-i", "-", "-c:a", "aac", "-b:a", "192k", "-f", "adts", "-",
    )

    fun durationUs(url: String): Long? = runCatching {
        val probe = ProcessBuilder(
            ffprobe,
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1",
            url,
        ).start()
        val seconds = probe.inputStream.bufferedReader().readText().trim().toDoubleOrNull()
        if (!probe.waitFor(PROBE_WAIT_S, TimeUnit.SECONDS)) probe.destroyForcibly()
        seconds?.let { (it * MICROS).toLong() }
    }.onFailure { Diag.warn("sound", "could not ask ffprobe for a length", it) }.getOrNull()

    fun silentAacFrame(): ByteArray? = runCatching {
        val made = ProcessBuilder(
            ffmpeg, "-v", "error", "-nostdin", "-f", "lavfi", "-i", "anullsrc=r=$SOUND_RATE:cl=stereo", "-t", "1",
            "-c:a", "aac", "-b:a", "192k", "-f", "adts", "-",
        ).start()
        val bytes = made.inputStream.readBytes()
        made.waitFor(PROBE_WAIT_S, TimeUnit.SECONDS)
        AdtsFrames.of(bytes).lastOrNull()
    }.onFailure { Diag.warn("sound", "could not make a silent AAC frame", it) }.getOrNull()

    fun toSink(job: SoundJob, sink: OutputStream, onDone: (failed: Boolean) -> Unit): Pipeline {
        val decoder = start(decodeCommand(job.url, job.startAtMs))
        val pump = PcmPump(chainFor(job.sound), SOUND_CHANNELS, SOUND_RATE)
        val pipeline = Pipeline(null, listOf(decoder), pump, job.label)
        Diag.log("sound", "${job.label}: decoding ${job.startAtMs}ms in, sound=${job.sound}, processing for the sink")
        thread(name = "sound-${job.label}", isDaemon = true) {
            val failed = runCatching { pump.run(decoder.inputStream, sink) { pipeline.wanted } }
                .onFailure { Diag.warn("sound", "${job.label}: pump stopped", it) }
                .isFailure
            runCatching { sink.close() }
            onDone(failed || (decoder.waitFor() != 0 && pipeline.wanted))
        }
        return pipeline
    }

    fun toAac(job: SoundJob): Pipeline {
        val decoder = start(decodeCommand(job.url, job.startAtMs))
        val encoder = start(encodeCommand())
        val pump = PcmPump(chainFor(job.sound), SOUND_CHANNELS, SOUND_RATE)
        val pipeline = Pipeline(encoder.inputStream, listOf(decoder, encoder), pump, job.label)
        Diag.log("sound", "${job.label}: decoding ${job.startAtMs}ms in, sound=${job.sound}, processing to AAC")
        thread(name = "sound-${job.label}", isDaemon = true) {
            runCatching { pump.run(decoder.inputStream, encoder.outputStream) { pipeline.wanted } }
                .onFailure { Diag.warn("sound", "${job.label}: pump stopped", it) }
            runCatching { encoder.outputStream.close() }
        }
        return pipeline
    }

    private fun start(command: List<String>): Process {
        val process = ProcessBuilder(command).start()
        thread(name = "ffmpeg-stderr-${process.pid()}", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    Diag.warn("sound", "ffmpeg ${process.pid()}: ${line.withoutApiKeys()}")
                }
            }
        }
        return process
    }
}

internal data class SoundJob(val label: String, val url: String, val sound: SoundSettings, val startAtMs: Long)

private fun bundledWeights(): SpeechWeights? = runCatching { Holder.weights }
    .onFailure { Diag.warn("sound", "speech model could not be loaded; silence is cut by level only", it) }
    .getOrNull()

private object Holder {
    val weights: SpeechWeights by lazy {
        SpeechWeights.bundled().also { Diag.log("sound", "speech model loaded for smart skip-silence") }
    }
}
