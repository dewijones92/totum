package com.dewijones92.totum.ytdlp

import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Downloads yielding to playback.
 *
 * The observed failure: tapping a queued item started its download, whose own `extract_info`
 * raced the play's extraction on one embedded interpreter, and the play waited 16 seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InteractiveFirstEngineTest {

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=xx-KSozQdNU")

    /** Lets a test hold an extraction open, which is the whole situation being modelled. */
    private class Controllable : YtDlpEngine {
        val extractStarted = CompletableDeferred<Unit>()
        val finishExtract = CompletableDeferred<Unit>()
        var downloadsStarted = 0

        override suspend fun versions() = EngineVersions("test", "test")

        override suspend fun warmUp() = Unit

        override suspend fun solveN(challenges: List<String>, playerUrl: String) = emptyMap<String, String>()

        override suspend fun extract(url: HttpUrl): ExtractionResult {
            extractStarted.complete(Unit)
            finishExtract.await()
            return ExtractionResult.Failure.UnsupportedUrl(url)
        }

        override suspend fun searchVideos(query: String, maxResults: Int) =
            VideoSearchResult.Success(emptyList())

        override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int) =
            ChannelResult.Failure.NotAChannel(url)

        override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
            downloadsStarted++
            emit(DownloadEvent.Completed(File("/tmp/out.m4a")))
        }
    }

    private fun request() = DownloadRequest(url, File("/tmp"), formatId = null)

    @Test
    fun `a download waits while an extraction is in flight, then runs`() = runTest {
        val inner = Controllable()
        val engine = InteractiveFirstEngine(inner)

        val extracting = launch { engine.extract(url) }
        inner.extractStarted.await()

        val downloading = launch { engine.download(request()).toList() }
        runCurrent()
        assertEquals("the download must not start while a play is resolving", 0, inner.downloadsStarted)

        inner.finishExtract.complete(Unit)
        extracting.join()
        downloading.join()

        assertEquals(1, inner.downloadsStarted)
    }

    @Test
    fun `with nothing in flight a download starts immediately`() = runTest {
        val inner = Controllable()
        val engine = InteractiveFirstEngine(inner)

        engine.download(request()).toList()

        assertEquals(1, inner.downloadsStarted)
    }

    /**
     * A steady trickle of interactive work must never starve downloads outright, so the wait
     * is bounded. Hitting the bound restores the old behaviour, which is no worse.
     */
    @Test
    fun `the wait is bounded, so interactive work cannot starve downloads`() = runTest {
        val inner = Controllable()
        val engine = InteractiveFirstEngine(inner, maxWaitMs = 5_000)

        val extracting = launch { engine.extract(url) }
        inner.extractStarted.await()
        val downloading = launch { engine.download(request()).toList() }

        runCurrent()
        assertEquals(0, inner.downloadsStarted)

        advanceTimeBy(5_001)
        assertEquals("must give up waiting rather than never download", 1, inner.downloadsStarted)

        inner.finishExtract.complete(Unit)
        extracting.join()
        downloading.join()
    }

    /**
     * A failed extraction that left the counter raised would block every download for the
     * whole timeout — one error becoming a stalled queue.
     */
    @Test
    fun `a warm-up reaches the real engine`() = runTest {
        val real = com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine()

        InteractiveFirstEngine(real).warmUp()

        assertEquals(1, real.warmUps)
    }

    @Test
    fun `an extraction that throws still releases the gate`() = runTest {
        val throwing = object : YtDlpEngine {
            var downloadsStarted = 0
            override suspend fun versions() = EngineVersions("test", "test")
            override suspend fun warmUp() = Unit
            override suspend fun solveN(challenges: List<String>, playerUrl: String) = emptyMap<String, String>()
            override suspend fun extract(url: HttpUrl): ExtractionResult = error("boom")
            override suspend fun searchVideos(query: String, maxResults: Int) =
                VideoSearchResult.Success(emptyList())
            override suspend fun fetchChannel(url: HttpUrl, maxVideos: Int) = ChannelResult.Failure.NotAChannel(url)
            override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
                downloadsStarted++
                emit(DownloadEvent.Completed(File("/tmp/out.m4a")))
            }
        }
        val engine = InteractiveFirstEngine(throwing)

        runCatching { engine.extract(url) }
        engine.download(request()).toList()

        assertTrue("the gate must be open again", throwing.downloadsStarted == 1)
    }
}
