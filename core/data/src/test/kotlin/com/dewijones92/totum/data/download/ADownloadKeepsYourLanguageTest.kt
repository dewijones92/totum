package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Downloading must fetch the audio language you chose.
 *
 * The language preference reached the RESOLVE path and stopped there. `EngineDownloadStrategy` — the
 * primary video strategy — handed yt-dlp a bare `bv*+ba/b`, so choosing German and downloading gave you
 * the English original. That is the worst place for it to be missing: a downloaded file is exactly the
 * one you cannot re-pick a track for, and offline there is no menu to correct it with.
 *
 * The selector prefers rather than demands. `[language^=de]` matches `de`, `de-DE` and the dubbed
 * variants, and the `/` alternative means a video with a single audio track downloads unchanged — most
 * videos, and the case that must not regress.
 */
class ADownloadKeepsYourLanguageTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    /** Records the selector the strategy asks for, which is the decision under test. */
    private class RecordingEngine : YtDlpEngine by FakeYtDlpEngine() {
        val selectors: MutableList<String?> = mutableListOf()

        override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
            selectors += request.formatId
            emit(DownloadEvent.Started(request.url))
        }
    }

    private fun item() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("vid"),
            sourceId = SourceId("youtube"),
            title = "a dubbed video",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=vid"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=vid")),
    )

    private suspend fun selectorWith(languages: List<String>, audioOnly: Boolean = false): String? {
        val engine = RecordingEngine()
        EngineDownloadStrategy(engine, preferredAudioLanguages = { languages })
            .download(item(), folder.newFile("out-${languages.joinToString()}-$audioOnly.mp4"), audioOnly)
            .toList()
        return engine.selectors.single()
    }

    /** THE case: German chosen, so the download must ask for German. */
    @Test
    fun `a chosen language reaches the download selector`() = runTest {
        val selector = selectorWith(listOf("de"))

        assertTrue(
            "the selector must prefer German audio, got: $selector",
            selector!!.contains("language^=de"),
        )
    }

    /** A regional tag still matches the base language, since YouTube labels tracks both ways. */
    @Test
    fun `a regional tag matches the base language`() = runTest {
        val selector = selectorWith(listOf("en-GB"))

        assertTrue("got: $selector", selector!!.contains("language^=en"))
    }

    /** The must-not-break case: no preference means the plain best-merged selector, unchanged. */
    @Test
    fun `no preference leaves the selector alone`() = runTest {
        val selector = selectorWith(emptyList())

        assertTrue("got: $selector", selector == "bv*+ba/b")
    }

    /** And it must FALL BACK, or a video with one audio track would fail to download at all. */
    @Test
    fun `the language is a preference, not a requirement`() = runTest {
        val selector = selectorWith(listOf("de"))

        assertTrue(
            "a video with no German track must still download, got: $selector",
            selector!!.endsWith("/bv*+ba/b"),
        )
    }

    /** Audio-only downloads get the same treatment — it is the same choice. */
    @Test
    fun `an audio-only download also prefers the language`() = runTest {
        val selector = selectorWith(listOf("de"), audioOnly = true)

        assertTrue("got: $selector", selector!!.contains("language^=de"))
    }
}
