package com.dewijones92.totum.longones

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.video.LengthCheck
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class VideoLengthsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1_000_000L
    private val asked = mutableListOf<String>()
    private var answer: LengthCheck = LengthCheck.Known(3000, wasLive = false, via = "test")

    private fun item(id: String = "abcdefghijk") = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    private fun lengths(file: File = File(folder.root, "lengths.json")) = VideoLengths(
        file,
        ask = { asked += it.id.value; answer },
        now = { clock },
    )

    @Test
    fun `a length is asked once and kept for good, across a restart`() = runTest {
        val file = File(folder.root, "lengths.json")

        val first = lengths(file).lookUp(item())
        val afterRestart = lengths(file).lookUp(item())

        assertEquals(VideoLengths.Looked.Known(50.minutes, asked = true), first)
        assertEquals(VideoLengths.Looked.Known(50.minutes, asked = false), afterRestart)
        assertEquals(1, asked.size)
    }

    @Test
    fun `a stream that is on now is set aside for an hour, then asked again`() = runTest {
        answer = LengthCheck.LiveNow("test")
        val lengths = lengths()

        val first = lengths.lookUp(item()) as VideoLengths.Looked.NotNow
        clock += 59 * 60_000L
        val during = lengths.lookUp(item()) as VideoLengths.Looked.NotNow
        clock += 2 * 60_000L
        lengths.lookUp(item())

        assertEquals(VideoLengths.Reason.LIVE_OR_UPCOMING, first.reason)
        assertTrue(first.asked)
        assertTrue(!during.asked)
        assertEquals(2, asked.size)
    }

    @Test
    fun `a look-up that could not be answered is retried after fifteen minutes, not at once`() = runTest {
        answer = LengthCheck.Unknown("backing off")
        val lengths = lengths()

        lengths.lookUp(item())
        lengths.lookUp(item())
        clock += 16 * 60_000L
        lengths.lookUp(item())

        assertEquals(2, asked.size)
    }

    @Test
    fun `a video that says it has no length is not asked again for hours and is never kept`() = runTest {
        answer = LengthCheck.SaysNone("refused")
        val lengths = lengths()

        val looked = lengths.lookUp(item()) as VideoLengths.Looked.NotNow
        clock += 5 * 60 * 60_000L
        lengths.lookUp(item())

        assertEquals(VideoLengths.Reason.NO_LENGTH, looked.reason)
        assertEquals(1, asked.size)
        assertNull(lengths.cached("abcdefghijk"))
    }

    @Test
    fun `a look-up that throws is unknown, not a crash`() = runTest {
        val lengths = VideoLengths(File(folder.root, "t.json"), ask = { error("boom") }, now = { clock })

        val looked = lengths.lookUp(item()) as VideoLengths.Looked.NotNow

        assertEquals(VideoLengths.Reason.UNKNOWN, looked.reason)
    }

    @Test
    fun `lengths the feeds already said are kept without asking and never overwrite what is known`() = runTest {
        val lengths = lengths()

        lengths.keep(mapOf("a" to 90.seconds, "b" to 2400.seconds))
        lengths.keep(mapOf("a" to 5.seconds, "zero" to 0.seconds))

        assertEquals(90.seconds, lengths.cached("a"))
        assertEquals(2400.seconds, lengths.cached("b"))
        assertNull(lengths.cached("zero"))
        assertEquals(0, asked.size)
    }
}
