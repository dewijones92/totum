package com.dewijones92.totum.cli.serve.sound

import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProcessedSoundEndToEndTest {

    private val dir = Files.createTempDirectory("processed-sound").toFile()
    private val processed = ProcessedSound(weights = { null })

    private fun clip(seconds: Int): File {
        val file = File(dir, "tone.wav")
        val made = ProcessBuilder(
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
            "-ar", "22050", "-ac", "1", file.path,
        ).redirectErrorStream(true).start()
        val said = made.inputStream.bufferedReader().readText()
        assertTrue("ffmpeg is needed for this test (CI installs it): $said", made.waitFor(30, TimeUnit.SECONDS))
        assertEquals(said, 0, made.exitValue())
        return file
    }

    private fun sinkBytes(file: File, startAtMs: Long, sound: SoundSettings): ByteArray {
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var failed = true
        processed.toSink(SoundJob("t", file.path, sound, startAtMs), out) {
            failed = it
            done.countDown()
        }
        assertTrue("the pipeline never finished", done.await(30, TimeUnit.SECONDS))
        assertEquals(false, failed)
        return out.toByteArray()
    }

    @Test
    fun `a clip decoded for the sink comes out as stereo 48 kHz, one second being 192000 bytes`() {
        val bytes = sinkBytes(clip(2), 0, SoundSettings(boost = VolumeBoost.AUTO))

        assertEquals(2 * 192_000.0, bytes.size.toDouble(), 6_000.0)
        assertEquals(0, bytes.size % 4)
    }

    @Test
    fun `starting a second in leaves a second less to play`() {
        val file = clip(2)

        val whole = sinkBytes(file, 0, SoundSettings(boost = VolumeBoost.AUTO))
        val fromOne = sinkBytes(file, 1_000, SoundSettings(boost = VolumeBoost.AUTO))

        assertEquals(192_000.0, (whole.size - fromOne.size).toDouble(), 8_000.0)
    }

    @Test
    fun `a clip encoded for a TV is ADTS AAC that ffprobe reads as the length it was`() {
        val pipeline = processed.toAac(SoundJob("t", clip(2).path, SoundSettings(boost = VolumeBoost.AUTO), 0))
        val aac = File(dir, "out.aac")
        pipeline.output!!.use { input -> aac.outputStream().use { input.copyTo(it) } }

        val bytes = aac.readBytes()
        assertTrue(bytes.size > 1_000)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xF0, bytes[1].toInt() and 0xF0)
        val probe = ProcessBuilder(
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=nw=1:nk=1",
            aac.path,
        ).redirectErrorStream(true).start()
        val seconds = probe.inputStream.bufferedReader().readText().trim().toDouble()
        assertEquals(2.0, seconds, 0.3)
    }

    @Test
    fun `stopping a pipeline ends its processes and says it is no longer wanted`() {
        val pipeline = processed.toAac(SoundJob("t", clip(2).path, SoundSettings(boost = VolumeBoost.AUTO), 0))

        pipeline.stop()

        assertEquals(false, pipeline.wanted)
        pipeline.stop()
    }
}
