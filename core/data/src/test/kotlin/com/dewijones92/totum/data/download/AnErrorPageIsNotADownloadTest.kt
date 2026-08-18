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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A 200 that hands back a web page is a FAILED download, not a downloaded episode.
 *
 * `HttpDownloadStrategy` checked only `response.isSuccessful`, so any 200 was written to disk and
 * emitted as `Downloaded` — no content-type check anywhere. Podcast enclosures are exactly where this
 * happens in the wild: a moved feed serving an HTML "this show has moved" page, a paywall interstitial,
 * a captive portal, a CDN error page. All of them 200.
 *
 * The consequence is worse than a bad file, and it is a LIVELOCK:
 *
 * 1. the item is recorded `Downloaded`, so the UI shows a green tick;
 * 2. `routeNow` then prefers the copy on disk over the stream;
 * 3. Media3 throws `UnrecognizedInputFormatException` — a `ParserException`, so an `IOException`, so
 *    recovery classes it `Unreachable` and retries;
 * 4. the retries replay the same file, then `playWithoutTheStream` re-routes to that same file, returns
 *    true, and resets `attempts` to 0;
 * 5. so the item never plays **and the queue never advances past it**. Only deleting the download
 *    escapes, and nothing tells the person that is what they need to do.
 *
 * The rule here rejects rather than allow-lists, deliberately. Real enclosures carry all sorts of
 * content types — `audio/mpeg`, `audio/mp4`, `video/mp4`, and very commonly
 * `application/octet-stream` — so an allow-list of "media" types would break working feeds, which is a
 * worse failure than the one being fixed. Only types that certainly cannot be media are refused.
 *
 * ⚠️ Deliberately NOT tested here: a body shorter than its `Content-Length`. OkHttp's own
 * `FixedLengthSource` throws on premature EOF, so that already lands in the `IOException` branch and
 * deletes the file. Asserting it would be testing OkHttp.
 */
class AnErrorPageIsNotADownloadTest {

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

    private suspend fun fetch(): Pair<List<DownloadState>, java.io.File> {
        val target = folder.newFile("ep1.mp3")
        val states = HttpDownloadStrategy(OkHttpClient()).download(episode(), target, audioOnly = true).toList()
        return states to target
    }

    /** THE case: an HTML page must not become a downloaded episode. */
    @Test
    fun `an html page served with 200 is a failure and the file is removed`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><body>This podcast has moved.</body></html>")
                .build(),
        )

        val (states, target) = fetch()

        assertTrue(
            "an HTML body must fail, not be recorded as Downloaded. Got: $states",
            states.last() is DownloadState.Failed,
        )
        assertFalse("and the unplayable file must not be left on disk", target.exists())
    }

    /**
     * The must-not-break case, and the reason this rejects rather than allow-lists.
     *
     * `application/octet-stream` is extremely common for real enclosures. A rule that only accepted
     * audio types would break working feeds — a worse outcome than the bug.
     */
    @Test
    fun `an octet-stream enclosure still downloads`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/octet-stream")
                .body("ID3 pretend mp3 bytes")
                .build(),
        )

        val (states, target) = fetch()

        assertTrue("octet-stream is a normal enclosure. Got: $states", states.last() is DownloadState.Downloaded)
        assertTrue(target.exists())
    }

    /** And an ordinary audio enclosure, so the guard cannot have broken the everyday path. */
    @Test
    fun `an audio enclosure still downloads`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "audio/mpeg")
                .body("ID3 pretend mp3 bytes")
                .build(),
        )

        val (states, _) = fetch()

        assertTrue("Got: $states", states.last() is DownloadState.Downloaded)
    }

    /** A server that states no type at all is trusted — refusing it would break feeds too. */
    @Test
    fun `an enclosure with no content type is trusted`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("ID3 pretend mp3 bytes").build())

        val (states, _) = fetch()

        assertTrue("Got: $states", states.last() is DownloadState.Downloaded)
    }

    /**
     * The failure must be classed PERMANENT, or the fix swaps one loop for another.
     *
     * A reason string that matches no marker is treated as transient: `QueueAutoDownloader` retries it
     * three times per session with unpersisted attempts — so, every launch, forever — `OfflineReadiness`
     * counts it `waiting` and the queue banner reads "N downloading…" indefinitely, and the row shows
     * nothing. That reproduces this test's own complaint that "nothing tells the person", by a different
     * route. A server sending HTML for an enclosure will send it again next time; asking again cannot help.
     */
    @Test
    fun `an html enclosure is a permanent failure, not something to retry forever`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><body>This podcast has moved.</body></html>")
                .build(),
        )

        val (states, _) = fetch()
        val failed = states.last() as DownloadState.Failed

        assertTrue(
            "\"${failed.reason}\" matches no permanence marker, so it would be retried on every launch",
            failed.isPermanent,
        )
    }
}
