package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The view count and publication date reaching the player, across the session boundary.
 *
 * Dewi, 2026-08-06: *"this additional detail must appear within video page also"*. Getting it there
 * is not a UI change: the app and the player live either side of a `MediaSession`, and only a small
 * fixed set of fields crosses it. There is no `MediaMetadata` field for a view count or a relative
 * publication date, so both ride in the metadata **extras** — and a Bundle key that is written but
 * never read, or read under a different name, compiles perfectly and delivers nothing at all.
 *
 * That failure mode is not hypothetical in this repo: the preload command was silently rejected for
 * exactly this shape of reason, and a whole test exists for it (`PreloadCommandReachesServiceTest`).
 * So this asserts the values come back out of the real session rather than trusting the round-trip.
 *
 * The URI is deliberately unreachable. Nothing here is about playing bytes — `setMediaItem` publishes
 * the metadata regardless of whether a single one ever arrives.
 */
class PlayerMetadataTest {

    /** Foreground, or the session may not be connected at all. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private val publishedAt = Instant.parse("2026-08-01T09:00:00Z")

    @Before
    fun waitForSession() = runBlocking(Dispatchers.Main) {
        val connected = withTimeoutOrNull(TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
        queue.clear()
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @Test
    fun `the view count and both date forms survive the session`() = runBlocking(Dispatchers.Main) {
        val id = "metadata-with-facts"

        val states = statesFor(id, until = { it.viewsText != null }) { controller.play(itemWithMetadata(id)) }
        val state = states.firstOrNull { it.viewsText != null }
        assertTrue("the session never published the item's facts at all: $states", state != null)

        assertEquals("the view count did not cross the session", "1.2M views", state!!.viewsText)
        assertEquals("the relative date did not cross the session", "5 days ago", state.publishedText)
        assertEquals("the absolute date did not cross the session", publishedAt, state.publishedAt)
    }

    /**
     * And absence stays absence.
     *
     * A Bundle cannot hold a null Long, so the publication instant travels as epoch millis with a
     * sentinel for "there wasn't one" — which is precisely the shape that turns a missing date into a
     * confident wrong one (1970, or 1ms after the epoch) if the sentinel is mishandled.
     */
    @Test
    fun `an item with no views or date reports neither rather than a placeholder`() =
        runBlocking(Dispatchers.Main) {
            val id = "metadata-with-nothing"

            val states = statesFor(id, until = { true }) {
                controller.play(itemWithMetadata(id, withMetadata = false))
            }

            assertTrue("the player never reported the item as current", states.isNotEmpty())
            // Against EVERY state it published, not one sample of them: an invented value in a later
            // publication is exactly as wrong, and the old single read could not have seen it.
            assertTrue("a view count was invented: $states", states.all { it.viewsText == null })
            assertTrue("a relative date was invented: $states", states.all { it.publishedText == null })
            assertTrue(
                "an epoch date was invented from the absent sentinel: $states",
                states.all { it.publishedAt == null },
            )
        }

    /**
     * Through the QUEUE, which is how it actually happens.
     *
     * The queue is what holds the listing, and `PlaybackQueue.route` is the only thing that starts
     * playback — so this covers the path a tap takes, not just a direct call.
     */
    @Test
    fun `playing from the queue carries the listing facts too`() = runBlocking(Dispatchers.Main) {
        val id = "metadata-via-queue"

        val states = statesFor(id, until = { it.viewsText != null }) {
            queue.playNow(PlayableItem(itemWithMetadata(id), PlayHandle.Podcast()))
        }
        val state = states.firstOrNull { it.viewsText != null }

        // An EMPTY list here is a different finding from a state without the facts: it means nothing was
        // ever published for this item, and with no network and no copy on disk that is the queue
        // REFUSING to play, which is correct behaviour rather than a metadata bug. Unlike the direct
        // plays above, this case needs a reachable stream, because routing is the thing under test.
        assertEquals(
            "the queue's own play path dropped the view count (states published: $states)",
            "1.2M views",
            state?.viewsText,
        )
        assertEquals("5 days ago", state?.publishedText)
    }

    /**
     * Every state the session published ABOUT [id], COLLECTED from the stream rather than sampled.
     *
     * A per-test id, and matched here, because the service outlives a single test and a StateFlow keeps
     * its last value — so waiting for "any state" would be handed the PREVIOUS test's, complete with its
     * view count, and pass while proving nothing.
     *
     * Collected, because what is being asserted is TRANSIENT and the old poll loop raced it twice over.
     * The item's URI is unreachable on purpose, and how long that takes to discover is a property of the
     * machine: locally DNS and connect take long enough that the state is still current when the loop
     * looks, while on CI there is no route at all, the load fails in **73ms**, the player goes idle and
     * the session lands on nothing — so `state.value` had already been torn down and the test failed with
     * "the player never reported the item as current". This class failed that way in two CI runs today
     * (15feb95, ef8e0cd), and turning the emulator's network off reproduces it exactly.
     *
     * And the facts do not necessarily arrive in the FIRST state for an id — the queue's path publishes
     * a bare one first — so the wait is for a state that satisfies [until] rather than for the id alone.
     * A list rather than a single state, so "no value was invented" can be asserted against everything
     * the session said instead of one sample of it.
     *
     * [start] is invoked once the collector is running, and the buffer is what stops StateFlow
     * conflating a publication away while the collector sits between emissions.
     */
    private suspend fun statesFor(
        id: String,
        until: (PlaybackState) -> Boolean,
        start: suspend () -> Unit,
    ): List<PlaybackState> = coroutineScope {
        val seen = mutableListOf<PlaybackState>()
        val collector = launch {
            controller.state.buffer(Channel.UNLIMITED).filterNotNull().collect { seen += it }
        }
        start()
        withTimeoutOrNull(TIMEOUT_MS) {
            while (seen.none { it.itemId.value == id && until(it) }) delay(POLL_MS)
        }
        collector.cancel()
        seen.filter { it.itemId.value == id }
    }

    private fun itemWithMetadata(id: String, withMetadata: Boolean = true) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("test"),
        title = "an item with facts about it",
        publishedAt = publishedAt.takeIf { withMetadata },
        publishedText = "5 days ago".takeIf { withMetadata },
        duration = null,
        author = "Novara Media",
        mediaUrl = HttpUrl.of("https://example.test/episode.mp3"),
        viewsText = "1.2M views".takeIf { withMetadata },
    )

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 150L
    }
}
