package com.dewijones92.totum

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether a shared link plays, and as which video.
 *
 * Both halves have cost a real report. A share that plays TWICE is worse than one that never
 * plays — it interrupts something you chose — and a share whose URL is not canonicalised becomes a
 * different video from the identical one already in your queue.
 */
class SharedLinkTest {

    private val watch = "https://www.youtube.com/watch?v=GGY17VD_9Bs"

    @Test
    fun `a shared watch link plays`() {
        assertEquals(watch, sharedWatchUrl(watch, alreadyHandled = false)?.value)
    }

    /** Share sheets send a sentence, not a bare URL. */
    @Test
    fun `a link inside a sentence is found`() {
        val text = "Check this out $watch pretty good"

        assertEquals(watch, sharedWatchUrl(text, alreadyHandled = false)?.value)
    }

    /**
     * Report 0.1.346: one shared link fired five times over five hours, barging a TED talk in over
     * whatever was playing. Clearing the activity's intent was not enough — the task keeps the one
     * it was launched with and redelivers it after the process is killed.
     */
    @Test
    fun `a share already handled is ignored`() {
        assertNull(sharedWatchUrl(watch, alreadyHandled = true))
    }

    /**
     * The share sheet's tracking parameter must not survive: the URL is the video's identity
     * everywhere, so `?si=` would make this a different video from the same one already queued.
     */
    @Test
    fun `a share sheet's tracking parameter is stripped`() {
        val shared = "https://youtu.be/GGY17VD_9Bs?si=aBcDeFgH"

        val url = sharedWatchUrl(shared, alreadyHandled = false)?.value

        assertEquals(false, url?.contains("si="))
    }

    @Test
    fun `a shorts link is a watch link`() {
        assertEquals(
            true,
            sharedWatchUrl("https://www.youtube.com/shorts/GGY17VD_9Bs", alreadyHandled = false) != null,
        )
    }

    @Test
    fun `a link that is not YouTube is ignored`() {
        assertNull(sharedWatchUrl("https://example.com/watch?v=abc", alreadyHandled = false))
    }

    @Test
    fun `text with no link at all is ignored`() {
        assertNull(sharedWatchUrl("no link here", alreadyHandled = false))
        assertNull(sharedWatchUrl(null, alreadyHandled = false))
    }

    /**
     * A link that cannot be resolved right now is queued by its id, not lost. Report 0.1.477: shared
     * with no network, 53s of retries, gone. The entry needs only the watch URL — the queue
     * re-resolves from it when it plays — so the title is the id until then.
     */
    @Test
    fun `an unresolvable share still becomes a queue entry by its id`() {
        val item = placeholderFor(HttpUrl.of(watch), SourceId("shared"))

        assertEquals("GGY17VD_9Bs", item?.id?.value)
        assertEquals(watch, item?.mediaUrl?.value)
        assertEquals("YouTube video GGY17VD_9Bs", item?.title)
    }

    /** And a URL that is not a video has nothing to queue — better nothing than a broken row. */
    @Test
    fun `a non-video link is not queued`() {
        assertNull(placeholderFor(HttpUrl.of("https://www.youtube.com/@NovaraMedia"), SourceId("shared")))
    }
}
