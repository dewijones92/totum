package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AnAutoCaptionIsNamedOnceTest {

    private val player = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Never Gonna Give You Up","lengthSeconds":"213"},
         "streamingData":{"adaptiveFormats":[]},
         "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
           {"baseUrl":"https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&lang=en","languageCode":"en",
            "vssId":"a.en","name":{"runs":[{"text":"English (auto-generated)"}]}},
           {"baseUrl":"https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&lang=de","languageCode":"de",
            "vssId":"a.de","name":{"runs":[{"text":"German"}]}}
         ]}}}
    """.trimIndent()

    @Test
    fun `an auto-generated track keeps YouTube's own name and gains the mark only when it lacks one`() {
        val tracks = (PlayerResponseParser.parse(player) as PlayerResult.Success).subtitles

        assertEquals(listOf("English (auto-generated)", "German (auto-generated)"), tracks.map { it.label })
    }
}
