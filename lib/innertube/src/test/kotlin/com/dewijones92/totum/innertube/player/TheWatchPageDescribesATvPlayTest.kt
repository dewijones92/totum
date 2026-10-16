package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TheWatchPageDescribesATvPlayTest {

    private val watchPage = """
        {"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[
          {"itemSectionRenderer":{"contents":[
            {"videoMetadataRenderer":{"videoId":"dQw4w9WgXcQ",
              "title":{"runs":[{"text":"Never Gonna Give You Up"}]},
              "owner":{"videoOwnerRenderer":{"title":{"runs":[{"text":"Rick Astley"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCuAXFkgsw1L7xaCfnd5JJOw"}}}}}},
            {"videoSecondaryInfoRenderer":{"descriptionBodyText":{"runs":[
              {"text":"The official video for "},{"text":"Never Gonna Give You Up"},{"text":"\nListen: https://x"}]}}}
          ]}}
        ]}}}}}
    """.trimIndent()

    private val tvPlayerWithoutDescription = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Never Gonna Give You Up","lengthSeconds":"213"},
         "streamingData":{"adaptiveFormats":[]}}
    """.trimIndent()

    @Test
    fun `the watch page's description is read with its title and channel`() {
        val details = WatchNextParser.details(watchPage, "dQw4w9WgXcQ")

        assertEquals("The official video for Never Gonna Give You Up\nListen: https://x", details?.description)
        assertEquals("Rick Astley", details?.author)
    }

    @Test
    fun `a TV player response with no description takes the watch page's`() {
        val described = WatchNextDetails("Never Gonna Give You Up", "Rick Astley", null, description = "From the page")

        val result = PlayerResponseParser.parse(tvPlayerWithoutDescription, described)

        assertEquals(
            "From the page",
            ((result as? PlayerResult.Success)?.details ?: error("not parsed: $result")).description
        )
    }
}
