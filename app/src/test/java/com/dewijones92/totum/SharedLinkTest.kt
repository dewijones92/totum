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
        assertEquals(watch, sharedWatchUrl(watch)?.value)
    }

    /** Share sheets send a sentence, not a bare URL. */
    @Test
    fun `a link inside a sentence is found`() {
        val text = "Check this out $watch pretty good"

        assertEquals(watch, sharedWatchUrl(text)?.value)
    }

    @Test
    fun `a share delivered just now is fresh`() {
        assertEquals(ShareArrival.FRESH, shareArrival(launchedFromHistory = false, restored = false))
    }

    /**
     * Report 0.1.514: the app cold-started from Recents and 48ms later replayed a share from an
     * earlier session, putting it over the news. The intent Recents replays is the task's own copy,
     * so no mark this process put on it can be there.
     */
    @Test
    fun `a share reopened from Recents is a replay`() {
        assertEquals(
            ShareArrival.REOPENED_FROM_RECENTS,
            shareArrival(launchedFromHistory = true, restored = false),
        )
    }

    /**
     * Report 0.1.346: one link fired five times over five hours. An activity rebuilt from saved
     * state gets back the intent it was first started with, whichever process rebuilds it.
     */
    @Test
    fun `a share on a restored activity is a replay`() {
        assertEquals(ShareArrival.RESTORED, shareArrival(launchedFromHistory = false, restored = true))
        assertEquals(
            ShareArrival.REOPENED_FROM_RECENTS,
            shareArrival(launchedFromHistory = true, restored = true),
        )
    }

    /**
     * The share sheet's tracking parameter must not survive: the URL is the video's identity
     * everywhere, so `?si=` would make this a different video from the same one already queued.
     */
    @Test
    fun `a share sheet's tracking parameter is stripped`() {
        val shared = "https://youtu.be/GGY17VD_9Bs?si=aBcDeFgH"

        val url = sharedWatchUrl(shared)?.value

        assertEquals(false, url?.contains("si="))
    }

    /** A share whose id is not the first parameter used to be thrown away before any resolve. */
    @Test
    fun `a link with the id after another parameter plays`() {
        assertEquals(watch, sharedWatchUrl("https://m.youtube.com/watch?feature=shared&v=GGY17VD_9Bs")?.value)
    }

    @Test
    fun `a shorts link is a watch link`() {
        assertEquals(
            true,
            sharedWatchUrl("https://www.youtube.com/shorts/GGY17VD_9Bs") != null,
        )
    }

    @Test
    fun `a link that is not YouTube is ignored`() {
        assertNull(sharedWatchUrl("https://example.com/watch?v=abc"))
    }

    @Test
    fun `text with no link at all is ignored`() {
        assertNull(sharedWatchUrl("no link here"))
        assertNull(sharedWatchUrl(null))
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

    @Test
    fun `a song shared from an album names the album's playlist too`() {
        val shared = "https://m.youtube.com/watch?v=RHlqxABCDEF&list=OLAK5uy_nMJmELuIq7hkr1fvMVh2JK50kfaoKr5yw&index=12"

        assertEquals("OLAK5uy_nMJmELuIq7hkr1fvMVh2JK50kfaoKr5yw", sharedAlbumPlaylistId(shared))
    }

    @Test
    fun `an album link with no song is still an album`() {
        val shared = "Listen on YouTube Music " +
            "https://music.youtube.com/playlist?list=OLAK5uy_l4Z8njnOK_Q-MOVqKoq9tU98JqO20Mvnw&si=x"

        assertEquals("OLAK5uy_l4Z8njnOK_Q-MOVqKoq9tU98JqO20Mvnw", sharedAlbumPlaylistId(shared))
    }

    @Test
    fun `an ordinary playlist or a plain video is not an album`() {
        assertNull(
            sharedAlbumPlaylistId("https://www.youtube.com/watch?v=RHlqxABCDEF&list=PL6VJ2PQw8x0qsMx8GC1cga3zN7k9AVyAE")
        )
        assertNull(sharedAlbumPlaylistId("https://youtu.be/RHlqxABCDEF"))
        assertNull(sharedAlbumPlaylistId(null))
    }
}
