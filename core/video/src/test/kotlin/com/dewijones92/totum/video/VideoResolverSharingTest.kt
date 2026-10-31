package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * One extraction, however many callers want it at once.
 *
 * A real report (0.1.226) showed the same video resolved twice, overlapping — the second
 * caller arrived while the first was still extracting, saw an empty cache and did the whole
 * thing again. At 10-20s per extraction with the JS runtime, that is a minute of duplicated
 * work for one video.
 */
class VideoResolverSharingTest {

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=abc")
    private val source = SourceId("s")

    /** Counts extractions and takes its time, so callers genuinely overlap. */
    private class SlowEngine(private val calls: AtomicInteger) : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            calls.incrementAndGet()
            delay(1_000)
            return ExtractionResult.Success(
                MediaMetadata(
                    id = "abc",
                    title = "A video",
                    uploader = null,
                    durationSeconds = 10,
                    thumbnailUrl = null,
                    formats = listOf(
                        MediaFormat("18", "mp4", 640, 360, true, true, null, "https://x.test/v", "avc1", "mp4a"),
                    ),
                ),
            )
        }
    }

    private fun resolver(calls: AtomicInteger) =
        VideoResolver(SlowEngine(calls), SkipSegmentSource { emptyList() })

    @Test
    fun `two callers at once share one extraction`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)

        val first = async { resolver.resolve(url, source, asked = "play") }
        val second = async { resolver.resolve(url, source, asked = "prefetch") }

        assertSame("both should get the same resolved object", first.await(), second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a caller arriving after it finished is served from cache, still one extraction`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)

        resolver.resolve(url, source)
        resolver.resolve(url, source)

        assertEquals(1, calls.get())
    }

    @Test
    fun `different videos are not conflated`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)
        val other = HttpUrl.of("https://www.youtube.com/watch?v=xyz")

        val a = async { resolver.resolve(url, source) }
        val b = async { resolver.resolve(other, source) }
        a.await()
        b.await()

        assertEquals(2, calls.get())
    }

    /**
     * The cache held exactly one video, so tapping through a few evicted each on the next
     * and going back re-extracted: a report showed one video extracted three times in under
     * a minute at 20-26s each.
     */
    @Test
    fun `going back to an earlier video does not re-extract it`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)
        val others = (1..4).map { HttpUrl.of("https://www.youtube.com/watch?v=other$it") }

        resolver.resolve(url, source)
        others.forEach { resolver.resolve(it, source) }
        resolver.resolve(url, source)

        assertEquals("the first video should still be cached", 1 + others.size, calls.get())
    }

    /**
     * A stream that died must be re-resolved, not served from cache.
     *
     * Report 0.1.277, and it is the reason `forget` exists. A video failed nine minutes in with
     * a source error; recovery retried three times over twenty seconds; every attempt logged
     * "cache hit … skipped extraction" and asked the SAME dead URL, failing identically each
     * time; the video was then skipped as unplayable. Recovery's entire purpose is to obtain a
     * NEW address, and the cache silently defeated it.
     *
     * The class comment had predicted this exact loop — "a stale URL 403s, and recovery only
     * re-resolves, so serving one from here would loop" — and a ten-minute TTL was no defence,
     * because a stream dies long before ten minutes are up.
     */
    @Test
    fun `forgetting a resolution makes the next resolve real again`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)

        resolver.resolve(url, source, asked = "play")
        resolver.resolve(url, source, asked = "play")
        assertEquals("the second play should be a cache hit", 1, calls.get())

        resolver.forget(url)
        resolver.resolve(url, source, asked = "play")

        assertEquals("after forgetting, it must extract again", 2, calls.get())
    }

    /** Forgetting something never resolved is harmless — recovery cannot know either way. */
    @Test
    fun `forgetting an unknown url does nothing`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)

        resolver.forget(url)
        resolver.resolve(url, source, asked = "play")

        assertEquals(1, calls.get())
    }

    @Test
    fun `tapping A then B then A again while both resolve still extracts A only once`() = runTest {
        val calls = AtomicInteger()
        val resolver = resolver(calls)
        val other = HttpUrl.of("https://www.youtube.com/watch?v=xyz")

        val a = async { resolver.resolve(url, source) }
        val b = async { resolver.resolve(other, source) }
        delay(100)
        val aAgain = async { resolver.resolve(url, source) }

        assertSame(a.await(), aAgain.await())
        b.await()
        assertEquals("one extraction each, however the taps interleave", 2, calls.get())
    }
}
