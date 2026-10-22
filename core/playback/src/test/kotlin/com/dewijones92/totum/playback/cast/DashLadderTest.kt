package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class DashLadderTest {

    private fun layout(height: Int) =
        StreamLayout(
            Container.WEBM,
            "vp9",
            ByteSpan(0, 219),
            ByteSpan(220, 4000),
            width = height * 16 / 9,
            height = height
        )

    private val audio = CastAudio.Processed("audio/init.mp4", "audio/\$Number\$.m4s", 44_100, 2, "mp4a.40.2", 176_128)

    @Test
    fun `a ladder is one video adaptation set the TV can switch within, each rung on its own path`() {
        val ladder = CastVideo.Ladder(
            listOf(
                1080,
                720,
                480
            ).mapIndexed { i, h -> CastVideo.Relayed(layout(h), "video/r$i", DashManifest.bandwidthFor(h)) },
        )
        val mpd = DashManifest.build(60_000, ladder, audio)

        val videoSets = Regex(
            """<AdaptationSet id="1" contentType="video"[^>]*>(.*?)</AdaptationSet>""",
            RegexOption.DOT_MATCHES_ALL
        )
            .findAll(mpd).toList()
        assertEquals(1, videoSets.size)
        val reps = Regex("""<Representation id="(v\d)"[^>]*height="(\d+)"[^>]*>\s*<BaseURL>([^<]+)</BaseURL>""")
            .findAll(
                videoSets.single().groupValues[1]
            ).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toList()
        assertEquals(
            listOf(Triple("v0", "1080", "video/r0"), Triple("v1", "720", "video/r1"), Triple("v2", "480", "video/r2")),
            reps,
        )
    }

    @Test
    fun `a rung's bandwidth is measured from its own length and duration when the URL carries them`() {
        val url = "https://rr1.googlevideo.com/videoplayback?itag=248&clen=75000000&dur=100.000&id=o-x"
        assertEquals(6_000_000, DashManifest.bandwidthFor(url, 1080))
        assertEquals(DashManifest.bandwidthFor(1080), DashManifest.bandwidthFor("https://x.test/v.webm", 1080))
        assertEquals(
            DashManifest.bandwidthFor(1080),
            DashManifest.bandwidthFor("https://rr1.googlevideo.com/videoplayback?clen=5&dur=0.0", 1080),
        )
    }
}
