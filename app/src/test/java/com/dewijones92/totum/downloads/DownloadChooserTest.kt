package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.settings.InMemoryAppPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadChooserTest {

    private val video = MediaItem(
        id = MediaItemId("peppa"),
        sourceId = SourceId("peppa"),
        title = "Peppa",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=peppa"),
    )
    private val episode = video.copy(id = MediaItemId("ep"), mediaUrl = HttpUrl.of("https://feed.test/ep.mp3"))

    private class Rig(scope: TestScope) {
        val prefs = InMemoryAppPreferences()
        val downloads = FakeDownloadManager()
        val chooser = DownloadChooser(prefs, downloads, scope)
        val asked = mutableListOf<DownloadChooser.Ask>()

        init {
            scope.backgroundScope.launch(
                UnconfinedTestDispatcher(scope.testScheduler)
            ) { chooser.asks.collect { asked += it } }
        }
    }

    @Test
    fun `something with a picture asks, and a remembered choice is used without asking`() = runTest {
        val rig = Rig(this)
        rig.chooser.request(video)
        testScheduler.advanceUntilIdle()
        assertEquals(1, rig.asked.size)
        assertEquals(emptyList<Pair<MediaItemId, Boolean>>(), rig.downloads.requested)

        rig.chooser.choose(rig.asked.single(), DownloadChoice.Video(720), remember = true)
        testScheduler.advanceUntilIdle()
        rig.chooser.request(video.copy(id = MediaItemId("next")))
        testScheduler.advanceUntilIdle()

        assertEquals(1, rig.asked.size)
        assertEquals(listOf<Int?>(720, 720), rig.downloads.requestedHeights)
        assertEquals(DownloadChoice.Video(720), rig.prefs.settings.value.downloadChoice)
    }

    @Test
    fun `a long-press asks anyway, and a remembered sound choice does not answer a video entry`() = runTest {
        val rig = Rig(this)
        rig.prefs.setDownloadChoice(DownloadChoice.Sound)

        rig.chooser.request(video, askAnyway = true)
        rig.chooser.request(video, videoOnly = true)
        testScheduler.advanceUntilIdle()

        assertEquals(2, rig.asked.size)
    }

    @Test
    fun `something with no picture downloads at once, in full`() = runTest {
        val rig = Rig(this)
        rig.chooser.request(episode)
        testScheduler.advanceUntilIdle()

        assertEquals(0, rig.asked.size)
        assertEquals(listOf(MediaItemId("ep") to false), rig.downloads.requested)
        assertNull(rig.downloads.requestedHeights.single())
    }

    @Test
    fun `a torrent's film is one file, so nothing is asked and nothing offered it cannot keep`() = runTest {
        val rig = Rig(this)
        val film = video.copy(
            id = MediaItemId("torrent:h:1"),
            mediaUrl = HttpUrl.of("https://home.test/ts/stream/Night.of.the.Living.Dead.1968.mkv?link=h&index=1&play"),
        )
        rig.chooser.request(film)
        testScheduler.advanceUntilIdle()

        assertEquals(0, rig.asked.size)
        assertEquals(listOf(MediaItemId("torrent:h:1") to false), rig.downloads.requested)
    }

    @Test
    fun `the choice survives being stored`() {
        listOf(DownloadChoice.Sound, DownloadChoice.Video(null), DownloadChoice.Video(1080)).forEach {
            assertEquals(it, DownloadChoice.parse(it.stored))
        }
        assertNull(DownloadChoice.parse("rubbish"))
    }
}
