package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineDownloadStrategyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = FakeYtDlpEngine()
    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=abc")

    /** A video as it reaches the strategy: the handle carries the watch URL, not mediaUrl. */
    private fun videoItem(url: HttpUrl? = watchUrl) = PlayableItem(
        MediaItem(
            id = MediaItemId("abc"),
            sourceId = SourceId("https://www.youtube.com/@chan"),
            title = "A video",
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        if (url == null) PlayHandle.Podcast() else PlayHandle.Video(url),
    )

    @Test
    fun `merges through the engine and moves the result onto target`() = runTest {
        engine.registerMedia(watchUrl, FakeYtDlpEngine.sampleMetadata(id = "abc"))
        val target = temp.newFile("out.media")

        val states = EngineDownloadStrategy(engine).download(videoItem(), target, audioOnly = false).toList()

        assertTrue(states.first() is DownloadState.Downloading)
        val done = states.last() as DownloadState.Downloaded
        assertEquals(target.absolutePath, done.localPath)
        assertTrue(target.exists())
        // The temp work directory is cleaned up.
        assertFalse(temp.root.resolve("out.part").exists())
    }

    @Test
    fun `reports failure when the item has no url`() = runTest {
        val states = EngineDownloadStrategy(engine)
            .download(videoItem(url = null), temp.newFile("x.media"), audioOnly = false).toList()

        assertTrue(states.single() is DownloadState.Failed)
    }

    @Test
    fun `surfaces an engine failure for an unresolvable video`() = runTest {
        val states = EngineDownloadStrategy(engine)
            .download(videoItem(), temp.newFile("x.media"), audioOnly = false).toList()

        assertTrue(states.last() is DownloadState.Failed)
    }

    @Test
    fun `an audio-only download asks for bestaudio and records the variant`() = runTest {
        engine.registerMedia(watchUrl, FakeYtDlpEngine.sampleMetadata(id = "abc"))
        val target = temp.newFile("out.media")

        val states = EngineDownloadStrategy(engine).download(videoItem(), target, audioOnly = true).toList()

        assertEquals("ba/b", engine.lastRequest?.formatId)
        val done = states.last() as DownloadState.Downloaded
        assertTrue("an audio-only file must be marked as such", done.audioOnly)
    }

    @Test
    fun `a download keeps its sponsor segments by default and says so`() = runTest {
        engine.registerMedia(watchUrl, FakeYtDlpEngine.sampleMetadata(id = "abc"))
        val done = EngineDownloadStrategy(engine)
            .download(
                videoItem(),
                temp.newFile("out.media"),
                audioOnly = false
            ).toList().last() as DownloadState.Downloaded
        assertEquals(emptySet<String>(), engine.lastRequest?.sponsorBlockCategories)
        assertFalse(done.sponsorSegmentsCut)
    }

    @Test
    fun `a download asked to cut segments records that it was cut`() = runTest {
        engine.registerMedia(watchUrl, FakeYtDlpEngine.sampleMetadata(id = "abc"))
        val done = EngineDownloadStrategy(engine, sponsorBlockCategories = setOf("sponsor"))
            .download(
                videoItem(),
                temp.newFile("out.media"),
                audioOnly = false
            ).toList().last() as DownloadState.Downloaded
        assertTrue(done.sponsorSegmentsCut)
    }
}
