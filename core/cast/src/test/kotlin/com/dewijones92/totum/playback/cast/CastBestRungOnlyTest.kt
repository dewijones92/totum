package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastBestRungOnlyTest {

    private val lg = CastScreen.of("OLED65C24LA")

    private val vp9At2160 = CastRung("https://g/vp9-2160", 2160, "vp09.00.50.08")
    private val vp9At1080 = CastRung("https://g/vp9-1080", 1080, "vp09.00.40.08")
    private val avcAt1080 = CastRung("https://g/avc-1080", 1080, "avc1.640028")
    private val avcAt720 = CastRung("https://g/avc-720", 720, "avc1.4d401f")
    private val av1At2160 = CastRung("https://g/av1-2160", 2160, "av01.0.12M.08")

    private val sent = mutableListOf<QualityLabel>()
    private val fetching = mutableListOf<Int>()
    private val rungs = CastRungs("film", onSent = { sent += it }, onFetching = { fetching += it })

    private fun layoutOf(rung: CastRung) = StreamLayout(
        Container.WEBM,
        rung.codec ?: "vp9",
        ByteSpan(0, 219),
        ByteSpan(220, 4_000),
        width = rung.height * 16 / 9,
        height = rung.height,
    )

    private fun choose(
        offered: List<CastRung>,
        screen: CastScreen? = lg,
        ceiling: Int? = null,
        probe: (CastRung) -> StreamLayout = ::layoutOf,
    ) = rungs.choose("https://g/phone-flowing", offered, screen, ceiling, probe)

    @Test
    fun `the TV is sent the one best rung, and its own address, never the phone's`() {
        assertTrue(choose(listOf(avcAt720, vp9At1080, vp9At2160)))

        assertEquals(listOf(vp9At2160.url), rungs.rungs.map { it.first.url })
        assertEquals(vp9At2160.url, rungs.urlOf(0))
        assertNull(rungs.urlOf(1))
        assertEquals(listOf(QualityLabel(2160, VideoCodecFamily.VP9)), sent)
    }

    @Test
    fun `with a single rung offered the TV still gets that rung's address, not the phone's flowing one`() {
        assertTrue(choose(listOf(vp9At1080)))

        assertEquals(vp9At1080.url, rungs.urlOf(0))
        assertNull(rungs.nextLowerHeight())
    }

    @Test
    fun `a codec the TV cannot show is never the best, whatever its height`() {
        choose(listOf(av1At2160, vp9At1080))

        assertEquals(vp9At1080.url, rungs.urlOf(0))
    }

    @Test
    fun `a height only the TV's other codec can reach is capped per codec`() {
        choose(listOf(avcAt1080, CastRung("https://g/avc-2160", 2160, "avc1.640033")))

        assertEquals(avcAt1080.url, rungs.urlOf(0))
    }

    @Test
    fun `at the same height the better codec wins`() {
        choose(listOf(avcAt1080, vp9At1080))

        assertEquals(vp9At1080.url, rungs.urlOf(0))
    }

    @Test
    fun `nothing the TV can show sends no picture and says so`() {
        assertFalse(choose(listOf(av1At2160)))

        assertTrue(rungs.rungs.isEmpty())
        assertNull(rungs.urlOf(0))
        assertTrue(sent.isEmpty())
        assertNull(rungs.nextLowerHeight())
    }

    @Test
    fun `a rung that is really a codec the TV cannot show once read is left out for the next`() {
        val unknown = listOf(CastRung("https://g/mystery-2160", 2160, null), vp9At1080)

        choose(unknown) { rung ->
            if (rung.url.contains("mystery")) layoutOf(av1At2160) else layoutOf(rung)
        }

        assertEquals(vp9At1080.url, rungs.urlOf(0))
    }

    @Test
    fun `a rung that cannot be read is left out for the next`() {
        choose(listOf(vp9At2160, vp9At1080)) { rung ->
            if (rung.height == 2160) error("403") else layoutOf(rung)
        }

        assertEquals(vp9At1080.url, rungs.urlOf(0))
    }

    @Test
    fun `before anything is chosen there is nothing lower to step to`() {
        assertNull(rungs.nextLowerHeight())
        assertEquals("none", rungs.showableSummary())
    }

    @Test
    fun `the next lower height is the tallest showable one under what is sent`() {
        choose(listOf(vp9At2160, vp9At1080, avcAt720, av1At2160))

        assertEquals(1080, rungs.nextLowerHeight())
    }

    @Test
    fun `stalling steps down one height at a time until there is none lower`() {
        val offered = listOf(vp9At2160, vp9At1080, avcAt720)
        val heights = mutableListOf<Int>()
        var ceiling: Int? = null
        while (true) {
            val next = CastRungs("step", onSent = {}, onFetching = {})
            next.choose("https://g/phone", offered, lg, ceiling, ::layoutOf)
            heights += next.rungs.single().first.height
            ceiling = next.nextLowerHeight() ?: break
        }

        assertEquals(listOf(2160, 1080, 720), heights)
    }

    @Test
    fun `a ceiling sends the best rung at or under it`() {
        choose(listOf(vp9At2160, vp9At1080, avcAt720), ceiling = 1080)

        assertEquals(vp9At1080.url, rungs.urlOf(0))
        assertEquals(720, rungs.nextLowerHeight())
    }

    @Test
    fun `once the best rung is read the lower ones are not read`() {
        val many = (1..12).map { CastRung("https://g/r$it", 2160 - it, "vp09.00.40.08") }
        var reads = 0

        choose(many) { rung ->
            reads++
            layoutOf(rung)
        }

        assertEquals(1, reads)
        assertEquals("https://g/r1", rungs.urlOf(0))
    }

    @Test
    fun `the TV fetching a rung is reported once per rung`() {
        choose(listOf(vp9At2160))

        rungs.fetched(0)
        rungs.fetched(0)
        rungs.fetched(5)

        assertEquals(listOf(2160), fetching)
    }

    @Test
    fun `with no screen known every offered rung is showable`() {
        choose(listOf(av1At2160, vp9At1080), screen = null)

        assertEquals(av1At2160.url, rungs.urlOf(0))
    }

    @Test
    fun `an offered list with the same address twice counts it once`() {
        choose(listOf(vp9At1080, vp9At1080, avcAt720))

        assertEquals("1080p · VP9, 720p · AVC", rungs.showableSummary())
    }
}
