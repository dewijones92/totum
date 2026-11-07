package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage
import org.junit.Assert.assertEquals
import org.junit.Test

class TvVideosTest {

    private class Published(
        val refused: Map<String, String>,
        val sent: Map<String, QualityLabel>,
        val fetching: Map<String, Int>,
        val dropped: Map<String, Int>,
    )

    private class RecordingTv : CastTvPort {
        val published = mutableListOf<Published>()
        override fun current(): CastTv? = null
        override fun videos(
            refused: Map<String, String>,
            sent: Map<String, QualityLabel>,
            fetchingHeights: Map<String, Int>,
            droppedFrom: Map<String, Int>,
        ) {
            published += Published(refused, sent, fetchingHeights, droppedFrom)
        }

        override fun problem(now: CastProblem?) = Unit
        override fun stage(mediaId: String, now: CastStage?, percent: Int?) = Unit
    }

    private val tv = RecordingTv()
    private val videos = TvVideos(tv)
    private val hd = QualityLabel.of(1080, "avc1.640028")

    @Test
    fun `a video sent is published with its label and clears an earlier refusal`() {
        videos.refused("a", "the TV cannot decode it")

        videos.sent("a", hd)

        val last = tv.published.last()
        assertEquals(emptyMap<String, String>(), last.refused)
        assertEquals(mapOf("a" to hd), last.sent)
    }

    @Test
    fun `a refusal removes what was sent and says why`() {
        videos.sent("a", hd)

        videos.refused("a", "sound only: VP9 needs a newer TV")

        val last = tv.published.last()
        assertEquals(mapOf("a" to "sound only: VP9 needs a newer TV"), last.refused)
        assertEquals(emptyMap<String, QualityLabel>(), last.sent)
    }

    @Test
    fun `sending with no label keeps what was known and still publishes`() {
        videos.sent("a", hd)
        val before = tv.published.size

        videos.sent("a", null)

        assertEquals(before + 1, tv.published.size)
        assertEquals(mapOf("a" to hd), tv.published.last().sent)
    }

    @Test
    fun `the height being fetched and the height stepped down from are published per video`() {
        videos.fetching("a", 2160)
        videos.steppedDown("a", 2160)
        videos.steppedDown("b", 1440)

        val last = tv.published.last()
        assertEquals(mapOf("a" to 2160), last.fetching)
        assertEquals(mapOf("a" to 2160, "b" to 1440), last.dropped)
    }

    @Test
    fun `forgetting a video drops all four facts about it and publishes once`() {
        videos.sent("a", hd)
        videos.fetching("a", 1080)
        videos.steppedDown("a", 2160)
        videos.refused("b", "no")
        val before = tv.published.size

        videos.forget("a")

        assertEquals(before + 1, tv.published.size)
        val last = tv.published.last()
        assertEquals(emptyMap<String, QualityLabel>(), last.sent)
        assertEquals(emptyMap<String, Int>(), last.fetching)
        assertEquals(emptyMap<String, Int>(), last.dropped)
        assertEquals(mapOf("b" to "no"), last.refused)
    }

    @Test
    fun `forgetting a video nothing is known about publishes nothing`() {
        val before = tv.published.size

        videos.forget("never-seen")

        assertEquals(before, tv.published.size)
    }

    @Test
    fun `clearing empties everything and publishes`() {
        videos.sent("a", hd)
        videos.refused("b", "no")

        videos.clear()

        val last = tv.published.last()
        assertEquals(emptyMap<String, QualityLabel>(), last.sent)
        assertEquals(emptyMap<String, String>(), last.refused)
    }
}
