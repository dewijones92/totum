package com.dewijones92.totum.playback

import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Dropping ONE video's held conversations, which is what a replay needs.
 *
 * Written because the function shipped with no test at all, and the obvious wrong call —
 * `forgetLiveSabrStreams()`, which clears every video and the give-up list with it — passed the
 * entire suite. Clearing every video would start a cold conversation for an item that is still
 * playing perfectly well, which is the sixteen-cold-opens failure
 * [AReopenContinuesTheSabrConversationTest] exists to prevent, arriving by a different door.
 *
 * Asserted by IDENTITY through `sabrStreamFor`, the same seam the routing uses, rather than by
 * reaching into the cache: a different instance back means the conversation was dropped, and the
 * same instance back means it was kept.
 */
class ForgettingOneVideosStreamsTest {

    @Before
    fun startWithNothingHeld() = forgetEverything()

    @After
    fun leaveNothingBehind() = forgetEverything()

    private fun forgetEverything() {
        SabrSessions.clear()
        forgetLiveSabrStreams()
    }

    private fun register(videoId: String) = SabrSessions.register(
        videoId,
        SabrSession(
            streamingUrl = "https://sabr.test/videoplayback",
            ustreamerConfig = byteArrayOf(1, 2, 3),
            audio = SabrFormat(AUDIO_ITAG, 1L),
            video = SabrFormat(VIDEO_ITAG, 2L, contentLength = STATED_LENGTH),
            durationMs = 600_000,
        ),
    )

    @Test
    fun `both tracks of the named video are dropped`() {
        register(REPLAYED)
        val video = SabrSessions.uriFor(REPLAYED, VIDEO_ITAG)!!
        val audio = SabrSessions.uriFor(REPLAYED, AUDIO_ITAG)!!
        val heldVideo = sabrStreamFor(video)
        val heldAudio = sabrStreamFor(audio)

        forgetLiveSabrStreamsFor(REPLAYED)

        assertNotSame("the video track kept its old conversation", heldVideo, sabrStreamFor(video))
        assertNotSame("the audio track kept its old conversation", heldAudio, sabrStreamFor(audio))
    }

    @Test
    fun `another video that is still playing keeps its conversation`() {
        register(REPLAYED)
        register(UNRELATED)
        val untouched = sabrStreamFor(SabrSessions.uriFor(UNRELATED, VIDEO_ITAG)!!)
        sabrStreamFor(SabrSessions.uriFor(REPLAYED, VIDEO_ITAG)!!)

        forgetLiveSabrStreamsFor(REPLAYED)

        assertSame(
            "dropping one item's streams must not restart an unrelated one mid-playback",
            untouched,
            sabrStreamFor(SabrSessions.uriFor(UNRELATED, VIDEO_ITAG)!!),
        )
    }

    /**
     * The key is `videoId:itag`, so a prefix match without the colon would take a longer id with
     * it. YouTube ids are a fixed eleven characters today, which is exactly what makes this the
     * kind of bug that waits.
     */
    @Test
    fun `a video whose id merely starts the same is left alone`() {
        register(SHORT_ID)
        register(LONGER_ID)
        val longer = sabrStreamFor(SabrSessions.uriFor(LONGER_ID, VIDEO_ITAG)!!)

        forgetLiveSabrStreamsFor(SHORT_ID)

        assertSame(longer, sabrStreamFor(SabrSessions.uriFor(LONGER_ID, VIDEO_ITAG)!!))
    }

    @Test
    fun `forgetting a video that holds nothing leaves everything else alone`() {
        register(UNRELATED)
        val untouched = sabrStreamFor(SabrSessions.uriFor(UNRELATED, VIDEO_ITAG)!!)

        forgetLiveSabrStreamsFor("never-played")

        assertSame(untouched, sabrStreamFor(SabrSessions.uriFor(UNRELATED, VIDEO_ITAG)!!))
    }

    private companion object {
        const val REPLAYED = "uSMGENDH_QI"
        const val UNRELATED = "jNQXAC9IVRw"
        const val SHORT_ID = "abc"
        const val LONGER_ID = "abcd"
        const val AUDIO_ITAG = 251
        const val VIDEO_ITAG = 137
        const val STATED_LENGTH = 1_000_000L
    }
}
