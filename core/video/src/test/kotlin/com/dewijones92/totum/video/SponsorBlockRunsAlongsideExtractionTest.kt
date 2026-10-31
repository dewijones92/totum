package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SponsorBlockRunsAlongsideExtractionTest {

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk")
    private val segment = SkipSegment(1.seconds, 2.seconds)

    private val slowEngine = object : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            delay(1_000)
            return ExtractionResult.Success(
                MediaMetadata(
                    id = "abcdefghijk",
                    title = "A video",
                    uploader = null,
                    durationSeconds = 10,
                    thumbnailUrl = null,
                    formats = listOf(
                        MediaFormat("18", "mp4", 640, 360, true, true, null, "https://x.test/v", "avc1", "mp4a")
                    ),
                ),
            )
        }
    }

    private val slowSegments = SkipSegmentSource {
        delay(1_000)
        listOf(segment)
    }

    @Test
    fun `the segment lookup does not wait for the extraction to finish`() = runTest {
        val resolved = VideoResolver(slowEngine, slowSegments).resolve(url, SourceId("s"))

        assertEquals(listOf(segment), resolved?.skipSegments)
        assertEquals(1_000L, currentTime)
    }
}
