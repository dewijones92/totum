package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class CastRungsTest {

    private val ultra = CastScreen.of("Chromecast Ultra")

    private fun layout(codecs: String, height: Int, container: Container) =
        StreamLayout(container, codecs, ByteSpan(0, 219), ByteSpan(220, 4000), width = height * 16 / 9, height = height)

    private val truth = mapOf(
        "https://g/a" to layout("av01.0.08M.08", 1080, Container.MP4),
        "https://g/b" to layout("vp9", 1080, Container.WEBM),
        "https://g/c" to layout("vp9", 720, Container.WEBM),
        "https://g/d" to layout("avc1.4d401f", 720, Container.MP4),
    )

    @Test
    fun `codecs the phone did not know are read from the streams before the TV is offered any`() {
        val offered = truth.keys.mapIndexed { i, url -> CastRung(url, truth.getValue(url).height!!, codec = null) }
        val rungs = CastRungs("x", onSent = {}, onFetching = {})

        rungs.choose("https://g/a", offered, ultra) { rung -> truth.getValue(rung.url) }

        assertEquals(listOf("vp9" to 1080, "vp9" to 720), rungs.rungs.map { it.second.codecs to it.second.height })
    }
}
