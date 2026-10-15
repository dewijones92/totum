package com.dewijones92.totum.polite

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliteYtDlpEngineTest {
    private var clock = 0L
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=C6pdB9oikdo")
    private var answer: ExtractionResult =
        ExtractionResult.Failure.Extractor("ERROR: [youtube] C6pdB9oikdo: Sign in to confirm you’re not a bot.")
    private var calls = 0
    private val inner = object : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            calls++
            return answer
        }
    }
    private val botBackOff = BotBackOff { clock }
    private val gate = PoliteGate(now = { clock }, sleep = { clock += it })
    private val engine = PoliteYtDlpEngine(inner, gate, botBackOff)

    @Test
    fun `after a bot check, anonymous extraction is not even tried until the back-off ends`() = runTest {
        engine.extract(url)
        val skipped = engine.extract(url)
        assertEquals(1, calls)
        assertTrue(skipped.toString(), skipped.toString().contains(PoliteYtDlpEngine.BACKING_OFF))
        clock += 60_001
        engine.extract(url)
        assertEquals(2, calls)
    }

    @Test
    fun `during a bot back-off a yt-dlp download is not tried, and says why`() = runTest {
        engine.extract(url)
        val events = engine.download(DownloadRequest(url = url, targetDirectory = java.io.File("unused"))).toList()
        val failed = events.single() as DownloadEvent.Failed
        assertTrue(failed.reason.toString(), failed.reason.toString().contains(PoliteYtDlpEngine.BACKING_OFF))
    }

    @Test
    fun `a 429 inside yt-dlp slows every YouTube call down`() = runTest {
        answer = ExtractionResult.Failure.Extractor("ERROR: [youtube] x: HTTP Error 429: Too Many Requests")
        engine.extract(url)
        assertTrue(gate.slowDown.value != null)
    }

    @Test
    fun `a success clears the back-off`() = runTest {
        engine.extract(url)
        clock += 60_001
        answer = ExtractionResult.Success(
            MediaMetadata(
                id = "C6pdB9oikdo",
                title = "t",
                uploader = null,
                durationSeconds = 10,
                thumbnailUrl = null,
                formats = emptyList(),
            ),
        )
        engine.extract(url)
        assertNull(botBackOff.until.value)
    }
}
