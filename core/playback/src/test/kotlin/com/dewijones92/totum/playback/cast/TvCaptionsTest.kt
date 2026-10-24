package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class TvCaptionsTest {

    private val youTubeAuto = """
        WEBVTT
        Kind: captions
        Language: en

        00:00:00.000 --> 00:00:02.389 align:start position:0%
         
        hello<00:00:00.560><c> everyone</c><00:00:01.120><c> and</c>

        00:00:02.389 --> 00:00:02.399 align:start position:0% line:85%
        hello everyone and
         
    """.trimIndent()

    @Test
    fun `cue settings and word timings are removed so the TV places captions itself`() {
        val cleaned = TvCaptions.clean(youTubeAuto)

        assertEquals(
            """
            WEBVTT
            Kind: captions
            Language: en

            00:00:00.000 --> 00:00:02.389
             
            hello everyone and

            00:00:02.389 --> 00:00:02.399
            hello everyone and
             
            """.trimIndent(),
            cleaned.text,
        )
        assertEquals(mapOf("align" to 2, "position" to 2, "line" to 1), cleaned.settingsRemoved)
        assertEquals(6, cleaned.inlineTagsRemoved)
    }

    @Test
    fun `plain captions pass through untouched`() {
        val plain = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nA plain line.\n"

        val cleaned = TvCaptions.clean(plain)

        assertEquals(plain, cleaned.text)
        assertEquals(emptyMap<String, Int>(), cleaned.settingsRemoved)
    }
}
