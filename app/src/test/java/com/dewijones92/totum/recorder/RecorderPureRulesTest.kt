package com.dewijones92.totum.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RecorderPureRulesTest {

    @Test
    fun `a tall phone screen is scaled so its long side is at most 1920 and keeps its shape`() {
        assertEquals(CaptureSize(864, 1920), captureSize(1080, 2400))
        assertEquals(CaptureSize(886, 1920), captureSize(1440, 3120))
    }

    @Test
    fun `landscape is scaled the same way round`() {
        assertEquals(CaptureSize(1920, 864), captureSize(2400, 1080))
    }

    @Test
    fun `a screen already within the cap is recorded at its own size`() {
        assertEquals(CaptureSize(1080, 1920), captureSize(1080, 1920))
        assertEquals(CaptureSize(720, 1280), captureSize(720, 1280))
        assertEquals(CaptureSize(1920, 1080), captureSize(1920, 1080))
    }

    @Test
    fun `an odd screen size is made even because the encoder wants it`() {
        assertEquals(CaptureSize(1080, 1920), captureSize(1081, 1921))
        val odd = captureSize(721, 1281)
        assertEquals(0, odd.width % 2)
        assertEquals(0, odd.height % 2)
    }

    @Test
    fun `a tiny screen never becomes smaller than two pixels each way`() {
        assertEquals(CaptureSize(2, 2), captureSize(1, 1))
        assertEquals(CaptureSize(2, 50), captureSize(1, 50))
    }

    @Test
    fun `a different cap is honoured`() {
        assertEquals(CaptureSize(540, 960), captureSize(1080, 1920, longSideCap = 960))
    }

    @Test
    fun `whatever the screen the result is even, within the cap and within one percent of the shape`() {
        listOf(
            1080 to 2340, 1080 to 2400, 1440 to 3088, 1344 to 2992, 800 to 1280, 2560 to 1600, 3840 to 2160,
            1812 to 2176, 1264 to 2780,
        ).forEach { (w, h) ->
            val size = captureSize(w, h)
            assertEquals("$w x $h width even", 0, size.width % 2)
            assertEquals("$w x $h height even", 0, size.height % 2)
            assertTrue("$w x $h long side", maxOf(size.width, size.height) <= 1920)
            val shape = w.toDouble() / h
            val got = size.width.toDouble() / size.height
            assertTrue("$w x $h shape $shape vs $got", kotlin.math.abs(shape - got) / shape < 0.01)
        }
    }

    private fun named(vararg parts: String) = "Totum-" + parts.joinToString("-") + ".mp4"

    @Test
    fun `a recording is named after the moment it started, to the second`() {
        assertEquals(named("2026", "11", "07", "04", "10", "05"), recordingName(LocalDateTime.of(2026, 11, 7, 4, 10, 5)))
        assertEquals(named("2026", "01", "02", "03", "04", "05"), recordingName(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
        assertNotEquals(
            recordingName(LocalDateTime.of(2026, 11, 7, 4, 10, 5)),
            recordingName(LocalDateTime.of(2026, 11, 7, 4, 10, 6)),
        )
    }

    @Test
    fun `an unfinished recording that is empty is deleted`() {
        assertEquals(OrphanVerdict.Delete("the file is empty"), orphanVerdict(0, 5_000))
        assertEquals(OrphanVerdict.Delete("the file is empty"), orphanVerdict(-1, null))
    }

    @Test
    fun `one with bytes but nothing playable in them is deleted`() {
        assertTrue(orphanVerdict(4_096, null) is OrphanVerdict.Delete)
        assertTrue(orphanVerdict(4_096, 0) is OrphanVerdict.Delete)
        assertTrue(orphanVerdict(4_096, -5) is OrphanVerdict.Delete)
        assertEquals(
            "4096 bytes but no playable video in them",
            (orphanVerdict(4_096, null) as OrphanVerdict.Delete).why,
        )
    }

    @Test
    fun `one with a playable length is published into the gallery`() {
        assertEquals(OrphanVerdict.Publish, orphanVerdict(1_000_000, 10_000))
        assertEquals(OrphanVerdict.Publish, orphanVerdict(1, 1))
    }
}
