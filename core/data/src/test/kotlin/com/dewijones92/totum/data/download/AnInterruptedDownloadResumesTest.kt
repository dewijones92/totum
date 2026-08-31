package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.isPermanent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A part-fetched download carries on from where it stopped instead of starting over.
 *
 * Podcast enclosures are routinely 60–150MB and are fetched over exactly the connection most
 * likely to drop: a phone, in the background, moving. Restarting from zero on every blip meant a
 * long episode on a poor signal could never finish however many times it was retried — the retry
 * budget was spent re-fetching the same first few megabytes. It also made the retries themselves
 * expensive, which is the reason to be timid about retrying, which is how a download quietly gives
 * up.
 *
 * The mechanism is a `.part` file plus HTTP Range, and the rule it has to obey is that a resume
 * must never corrupt: bytes are only appended when the server answers `206` from exactly the
 * offset asked for. Anything else — a `200` with the whole body, a `416` — starts clean.
 */
class AnInterruptedDownloadResumesTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun episode() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("ep-1"),
            sourceId = SourceId("a-feed"),
            title = "an episode",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of(server.url("/ep1.mp3").toString()),
        ),
        handle = PlayHandle.Podcast(),
    )

    /** The manager names the target; the strategy chooses where the partly-fetched bytes live. */
    private fun target() = File(folder.root, "ep1.media")

    private fun partOf(target: File) = File("${target.path}.part")

    private suspend fun fetch(target: File, resumable: Boolean = true): List<DownloadState> =
        HttpDownloadStrategy(OkHttpClient(), resumable = resumable)
            .download(episode(), target, audioOnly = true)
            .toList()

    private companion object {
        /** Comfortably more than the strategy's 256KB progress step, so progress is actually emitted. */
        const val ALREADY_ON_DISK = 900_000
    }

    /** THE case: half the episode is already on disk, so only the rest is asked for. */
    @Test
    fun `an existing part file is continued, not re-fetched`() = runTest {
        val target = target()
        partOf(target).writeText("HEAD")
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes 4-11/12")
                .setHeader("Content-Type", "audio/mpeg")
                .body("TAILTAIL")
                .build(),
        )

        val states = fetch(target)

        assertEquals("the server must be asked for the rest only", "bytes=4-", server.takeRequest().headers["Range"])
        assertTrue("got $states", states.last() is DownloadState.Downloaded)
        assertEquals("HEADTAILTAIL", target.readText())
        assertFalse("and the part file is gone once it is whole", partOf(target).exists())
    }

    /**
     * Progress is the WHOLE download's progress, not this attempt's.
     *
     * A resumed 90%-complete episode reporting 0% is a lie the UI shows and the diagnostics record,
     * and it is indistinguishable from a download that keeps restarting — which is the exact bug
     * this feature exists to fix, so it must not look like it.
     */
    @Test
    fun `progress counts the bytes already on disk`() = runTest {
        val target = target()
        partOf(target).writeBytes(ByteArray(ALREADY_ON_DISK))
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes 900000-1499999/1500000")
                .body("x".repeat(600_000))
                .build(),
        )

        val progress = fetch(target).filterIsInstance<DownloadState.Downloading>()

        assertTrue("nothing may report less than what is already on disk. Got: $progress", progress.isNotEmpty())
        assertTrue(
            "progress restarted from zero: $progress",
            progress.all { it.downloadedBytes >= ALREADY_ON_DISK },
        )
        assertEquals(
            "and the total is the whole file, not the remainder",
            1_500_000L,
            progress.first().totalBytes,
        )
    }

    /** A server that ignores Range answers 200 with everything — which must REPLACE, never append. */
    @Test
    fun `a server that ignores the range restarts cleanly`() = runTest {
        val target = target()
        partOf(target).writeText("STALE")
        server.enqueue(MockResponse.Builder().code(200).body("WHOLEFILE").build())

        val states = fetch(target)

        assertTrue("got $states", states.last() is DownloadState.Downloaded)
        assertEquals("the stale head must not survive", "WHOLEFILE", target.readText())
    }

    /**
     * 416 means the part is longer than the resource — the feed replaced the file, or the part is
     * junk. Dropping it makes the next attempt a clean one; keeping it would wedge the item forever.
     */
    @Test
    fun `a refused range drops the part file and stays retryable`() = runTest {
        val target = target()
        partOf(target).writeText("TOO LONG FOR THIS RESOURCE")
        server.enqueue(MockResponse.Builder().code(416).build())

        val states = fetch(target)

        val failure = states.last()
        assertTrue("got $states", failure is DownloadState.Failed)
        assertFalse("the next attempt must be allowed", (failure as DownloadState.Failed).isPermanent)
        assertFalse("and it must start from nothing", partOf(target).exists())
    }

    /**
     * The point of keeping the part file: a dropped connection must leave the bytes behind.
     *
     * It used to delete what it had, which is why every retry started at zero.
     */
    @Test
    fun `a dropped connection leaves the bytes for next time`() = runTest {
        val target = target()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                // Content-Length AFTER the body, or `body()` overwrites it: the point is a response
                // that promises far more than the socket ever delivers, which is what a connection
                // dropping mid-episode looks like to OkHttp.
                .body("x".repeat(300_000))
                .setHeader("Content-Length", "99999999")
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )

        val states = fetch(target)

        assertTrue("a truncated body is a failure. Got: $states", states.last() is DownloadState.Failed)
        assertTrue("what did arrive must be kept for the retry", partOf(target).length() > 0)
        assertFalse("and nothing half-finished may look like a download", target.exists())
    }

    /**
     * Resuming is opt-out for a reason: a URL that is RE-RESOLVED between attempts may not name the
     * same bytes. The signed-in fallback route asks YouTube again each time and can be handed a
     * different audio format, and appending one format's bytes to another's is a file that will
     * never play — a corruption no retry can detect. So that route is constructed non-resumable and
     * this proves the switch actually stops the Range request.
     */
    @Test
    fun `a non-resumable route never sends a range`() = runTest {
        val target = target()
        partOf(target).writeText("FROM A DIFFERENT FORMAT")
        server.enqueue(MockResponse.Builder().code(200).body("WHOLEFILE").build())

        fetch(target, resumable = false)

        assertNull("a re-resolved URL must never be resumed", server.takeRequest().headers["Range"])
        assertEquals("WHOLEFILE", target.readText())
    }
}
