package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.data.targets.HandedResolve
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolveHandOverTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val watch = HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk")
    private val start = 1_800_000_000_000L
    private var clock = start

    private val phone by lazy { FileLookupStore(folder.newFolder("phone")) }
    private val pi by lazy { FileLookupStore(folder.newFolder("pi")) }

    private fun stored(savedAt: Long = start) = StoredLookup(
        MediaMetadata(
            id = "abcdefghijk",
            title = "A video",
            uploader = "A channel",
            durationSeconds = 600,
            thumbnailUrl = null,
            formats = listOf(
                MediaFormat(
                    "18", "mp4", 640, 360, true, true, 1234,
                    "https://rr1.googlevideo.com/videoplayback?expire=${(start + 6 * 3_600_000) / 1000}&itag=18",
                    "avc1", "mp4a", "en", 10,
                ),
            ),
        ),
        SourceId("s"),
        emptyList(),
        savedAt,
    )

    private fun video(handle: PlayHandle = PlayHandle.Video(watch)) = PlayableItem(
        MediaItem(MediaItemId("abcdefghijk"), SourceId("s"), "A video", publishedAt = null, duration = null),
        handle,
    )

    private fun handOver(store: LookupStore) = ResolveHandOver(store, now = { clock })

    @Test
    fun `the phone offers the resolve it stored for a video`() {
        phone.save(watch, stored())

        val offered = handOver(phone).offer(video())

        assertEquals(watch.value, offered?.watchUrl)
        assertEquals("A video", FileLookupStore.decode(offered!!.lookup)?.metadata?.title)
    }

    @Test
    fun `nothing is offered for something that is not a video, or has no stored resolve, or is old`() {
        phone.save(watch, stored())

        assertNull(handOver(phone).offer(video(PlayHandle.Podcast())))
        assertNull(handOver(pi).offer(video()))
        clock += 5 * 3_600_000L
        assertNull(handOver(phone).offer(video()))
    }

    @Test
    fun `a handed resolve whose stream answers here is kept`() = runTest {
        phone.save(watch, stored())
        val handed = handOver(phone).offer(video())!!

        val kept = handOver(pi).accept(listOf(handed)) { 206 }

        assertEquals(1, kept)
        assertNotNull(pi.load(watch))
    }

    @Test
    fun `a handed resolve whose stream is refused here is dropped, so the Pi resolves for itself`() = runTest {
        phone.save(watch, stored())
        val handed = handOver(phone).offer(video())!!

        val kept = handOver(pi).accept(listOf(handed)) { 403 }

        assertEquals(0, kept)
        assertNull(pi.load(watch))
    }

    @Test
    fun `a handed resolve is dropped when the stream cannot be reached at all`() = runTest {
        phone.save(watch, stored())
        val handed = handOver(phone).offer(video())!!

        assertEquals(0, handOver(pi).accept(listOf(handed)) { null })
        assertNull(pi.load(watch))
    }

    @Test
    fun `a resolve the Pi holds that is as new is not replaced`() = runTest {
        phone.save(watch, stored())
        pi.save(watch, stored(savedAt = start + 1))
        val handed = handOver(phone).offer(video())!!

        assertEquals(0, handOver(pi).accept(listOf(handed)) { 206 })
        assertEquals(start + 1, pi.load(watch)?.savedAtMs)
    }

    @Test
    fun `an unreadable handed resolve is ignored`() = runTest {
        val kept = handOver(pi).accept(listOf(HandedResolve(watch.value, "not json"))) { 206 }

        assertEquals(0, kept)
        assertNull(pi.load(watch))
    }

    @Test
    fun `a Pi that accepted a handed resolve plays it without extracting`() = runTest {
        phone.save(watch, stored())
        handOver(pi).accept(listOf(handOver(phone).offer(video())!!)) { 206 }
        var extractions = 0
        val engine = object : YtDlpEngine by FakeYtDlpEngine() {
            override suspend fun extract(url: HttpUrl): ExtractionResult {
                extractions++
                return ExtractionResult.Success(stored().metadata)
            }
        }

        val resolved = VideoResolver(
            engine,
            SkipSegmentSource { emptyList() },
            now = { clock },
            lookupStore = pi,
        ).resolve(watch, SourceId("s"))

        assertEquals(0, extractions)
        assertTrue(resolved != null)
    }
}
