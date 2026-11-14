package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.settings.InMemoryAppPreferences
import com.dewijones92.totum.video.VideoQuality
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadChooserQualityForTest {

    private val item = MediaItem(
        id = MediaItemId("v"),
        sourceId = SourceId("s"),
        title = "A video",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=v"),
    )

    private fun rung(height: Int, codec: String?) = VideoQuality(
        id = "$height",
        label = "${height}p",
        height = height,
        videoUrl = HttpUrl.of("https://x.test/$height"),
        audioUrl = null,
        codec = codec,
    )

    private val ladder = listOf(rung(2160, "av01.0.12M.08"), rung(1080, "vp09.00.40.08"), rung(720, "avc1.64001F"))

    private fun chooser(known: (MediaItem) -> List<VideoQuality>) = DownloadChooser(
        InMemoryAppPreferences(),
        FakeDownloadManager(),
        TestScope(),
        knownQualities = known,
    )

    @Test
    fun `with no cap the best rung of the held ladder is named with its codec`() {
        assertEquals(QualityLabel(2160, VideoCodecFamily.AV1), chooser { ladder }.qualityFor(item, null))
    }

    @Test
    fun `a cap names the tallest rung at or under it, with that rung's codec`() {
        val chooser = chooser { ladder }

        assertEquals(QualityLabel(1080, VideoCodecFamily.VP9), chooser.qualityFor(item, 1080))
        assertEquals(QualityLabel(1080, VideoCodecFamily.VP9), chooser.qualityFor(item, 1440))
        assertEquals(QualityLabel(720, VideoCodecFamily.AVC), chooser.qualityFor(item, 720))
    }

    @Test
    fun `a cap below every rung names nothing`() {
        assertNull(chooser { ladder }.qualityFor(item, 480))
    }

    @Test
    fun `with no ladder held nothing is named, whatever the cap`() {
        val chooser = chooser { emptyList() }

        assertNull(chooser.qualityFor(item, null))
        assertNull(chooser.qualityFor(item, 720))
    }

    @Test
    fun `a rung whose codec is not stated is named by its height alone`() {
        val chooser = chooser { listOf(rung(480, null)) }

        assertEquals("480p", chooser.qualityFor(item, null)?.text)
    }

    @Test
    fun `the ladder is asked for the item being downloaded`() {
        val asked = mutableListOf<MediaItemId>()
        val chooser = chooser { asking ->
            asked += asking.id
            ladder
        }

        chooser.qualityFor(item, 720)

        assertEquals(listOf(item.id), asked)
    }
}
