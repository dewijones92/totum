package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The preload nomination has to cross a process-shaped boundary, and only a device can show it.
 *
 * The app cannot preload anything itself: only the SERVICE owns media sources, and a
 * `MediaController` cannot be handed one. So the app names what is coming over a custom session
 * command and the service builds it. That handoff is the whole new mechanism, and it is exactly the
 * kind of thing that compiles perfectly and silently does nothing — a command the session never
 * advertised is rejected without a word.
 *
 * Asserting the service's own breadcrumb is what makes this real: it is written after
 * `DefaultPreloadManager` has accepted the item, so the line existing means the command arrived,
 * was permitted, and the manager took it.
 */
class PreloadCommandReachesServiceTest {

    /** Foreground, or the session may not be connected at all. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val controller get() = (app.container).playbackController

    @Before
    fun waitForSession() = runBlocking(Dispatchers.Main) {
        val connected = withTimeoutOrNull(TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
        Breadcrumbs.clear()
    }

    @Test
    fun `nominating the next item reaches the service and is accepted`() = runBlocking(Dispatchers.Main) {
        controller.preloadNext(MediaItemId("nominated-item"), HttpUrl.of("https://example.test/next-episode.mp3"))

        val held = withTimeoutOrNull(TIMEOUT_MS) {
            while (Breadcrumbs.snapshot().none { "holding the first" in it.message }) delay(POLL_MS)
            true
        } ?: false

        assertTrue(
            "the preload command never reached the service. A session command that was not " +
                "advertised in onConnect is rejected silently, which looks exactly like this. " +
                "Trail: " + Breadcrumbs.snapshot().map { it.message }.takeLast(TRAIL_LINES),
            held,
        )
    }

    /**
     * And it lets go once that item is the one playing.
     *
     * Held bytes and played bytes are the same bytes: while an item plays, the player loads it
     * itself, so anything the preloader still holds for it is a second copy on a 192-256MB heap.
     * It was released only when something ELSE was nominated — never, for the last item in a queue
     * — and 0.1.346 died of OutOfMemoryError with that doubling live (2026-08-06).
     *
     * The nominated URI is deliberately unreachable: this is about the bookkeeping, and a
     * transition happens whether or not any byte ever arrives.
     */
    @Test
    fun `playing the nominated item plays the held source`() = runBlocking(Dispatchers.Main) {
        // An id and URI of their OWN, not the ones the test above nominates: the service outlives a
        // single test, and `hold` correctly ignores a re-nomination of what it is already holding —
        // so sharing them made this test's setup fail for a reason nothing to do with it.
        val itemId = MediaItemId("preloaded-item")
        val uri = HttpUrl.of("https://example.test/released-episode.mp3")
        controller.preloadNext(itemId, uri)
        assertTrue("setup: it must be held before releasing it means anything", awaitTrail("holding the first"))

        controller.play(itemPlaying(itemId, uri))

        assertTrue(
            "playing what was preloaded must play the HELD source, or the preloaded bytes are thrown " +
                "away and fetched again. Trail: " + Breadcrumbs.snapshot().map { it.message }.takeLast(TRAIL_LINES),
            awaitTrail("playing preloaded-item from the source held"),
        )
        assertTrue(
            "and the preloader must let go of it once the player owns it. Trail: " +
                Breadcrumbs.snapshot().map { it.message }.takeLast(TRAIL_LINES),
            awaitTrail("the player owns preloaded-item's preloaded source now"),
        )
    }

    /**
     * The release must key on the ITEM, because the URL is never the same twice.
     *
     * This is the test that was missing, and its absence is why the defect shipped: the case above
     * nominates and plays the SAME URL, which real playback does not do. A video's stream URL is
     * re-resolved for playback and comes back signed, expiring and often at a different itag, so the
     * held URL and the played URL differ every time — and while the release compared them, it could
     * never match. Report 0.1.359 has three `still holding … — what started is …` lines where both
     * URLs are the same video at itags 18 and 399, so the preloaded bytes stayed on a heap that was
     * already at 255MB of 256MB.
     */
    @Test
    fun `the held copy is released even though the stream url resolved differently`() =
        runBlocking(Dispatchers.Main) {
            val itemId = MediaItemId("re-resolved-item")
            controller.preloadNext(itemId, HttpUrl.of("https://example.test/muxed.mp4?itag=18&expire=1"))

            assertTrue("setup: nothing was held", awaitTrail("holding the first"))
            Breadcrumbs.clear()

            // The same item, resolved to a different stream — which is the normal case.
            controller.play(itemPlaying(itemId, HttpUrl.of("https://example.test/video.mp4?itag=399&expire=2")))

            assertTrue(
                "the held copy must be released when its ITEM starts playing, whatever URL that " +
                    "item resolved to. Trail: " +
                    Breadcrumbs.snapshot().map { it.message }.takeLast(TRAIL_LINES),
                awaitTrail("released the held copy"),
            )
        }

    private fun itemPlaying(id: MediaItemId, url: HttpUrl) = MediaItem(
        id = id,
        sourceId = SourceId("test"),
        title = "the nominated item",
        publishedAt = null,
        duration = null,
        mediaUrl = url,
    )

    private suspend fun awaitTrail(fragment: String): Boolean = withTimeoutOrNull(TIMEOUT_MS) {
        while (Breadcrumbs.snapshot().none { fragment in it.message }) delay(POLL_MS)
        true
    } ?: false

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 200L
        const val TRAIL_LINES = 6
    }
}
