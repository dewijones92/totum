package com.dewijones92.totum.ui.player

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Is there a picture?" — the question that decides whether fullscreen holds.
 *
 * Report 0.1.374 is one row of this table getting the wrong answer. The Black Hat talk ended at
 * 18:52:31, the next item's stream came back `Expired`, the player went idle at 18:52:35.6 while
 * a re-resolve ran, and fullscreen was dropped 1.1s later on the strength of `!hasVideo &&
 * !isBuffering`. The re-resolve succeeded at 18:52:39 and the video played on, windowed.
 */
class VideoPresenceTest {

    @Test
    fun `a playing video is showing video`() {
        assertEquals(VideoPresence.SHOWING_VIDEO, state(hasVideo = true, isPlaying = true).videoPresence())
    }

    @Test
    fun `a video keeps its picture even while buffering mid-item`() {
        assertEquals(
            VideoPresence.SHOWING_VIDEO,
            state(hasVideo = true, isBuffering = true, isPlaying = false).videoPresence(),
        )
    }

    @Test
    fun `the gap between items is waiting, not audio`() {
        // A yt-dlp resolve is 3-11s on a phone, and the whole of it looks like "no video".
        assertEquals(
            VideoPresence.WAITING,
            state(hasVideo = false, isBuffering = true, isPlaying = false).videoPresence(),
        )
    }

    @Test
    fun `a stream that failed and is being re-resolved is waiting, not audio`() {
        // THE BUG, exactly as report 0.1.374 recorded it: idle, so not buffering, and not
        // playing — which the old rule read as "this must be a podcast".
        assertEquals(
            VideoPresence.WAITING,
            state(hasVideo = false, isBuffering = false, isPlaying = false).videoPresence(),
        )
    }

    @Test
    fun `a video paused between items is waiting`() {
        assertEquals(
            VideoPresence.WAITING,
            state(hasVideo = false, isBuffering = false, isPlaying = false, kind = MediaKind.VIDEO).videoPresence(),
        )
    }

    @Test
    fun `a podcast that has started is settled on audio`() {
        assertEquals(
            VideoPresence.SETTLED_ON_AUDIO,
            state(hasVideo = false, isPlaying = true, kind = MediaKind.PODCAST).videoPresence(),
        )
    }

    @Test
    fun `a PAUSED podcast is still settled on audio`() {
        // A podcast can never grow a picture, so waiting for one would keep a black fullscreen
        // frame up for as long as it stayed paused.
        assertEquals(
            VideoPresence.SETTLED_ON_AUDIO,
            state(hasVideo = false, isPlaying = false, kind = MediaKind.PODCAST).videoPresence(),
        )
    }

    @Test
    fun `a podcast still loading is waiting, not yet settled`() {
        // Buffering wins over the kind: it has not settled into anything yet, and leaving
        // fullscreen a moment early is the failure this whole type exists to avoid.
        assertEquals(
            VideoPresence.WAITING,
            state(hasVideo = false, isBuffering = true, kind = MediaKind.PODCAST).videoPresence(),
        )
    }

    @Test
    fun `a video played in Listen mode settles on audio`() {
        // Still MediaKind.VIDEO, but there is genuinely no picture to be fullscreen about.
        assertEquals(
            VideoPresence.SETTLED_ON_AUDIO,
            state(hasVideo = false, isPlaying = true, kind = MediaKind.VIDEO).videoPresence(),
        )
    }

    @Test
    fun `a video whose track list arrives late is showing video the moment it does`() {
        assertEquals(
            VideoPresence.SHOWING_VIDEO,
            state(hasVideo = true, isBuffering = false, isPlaying = false).videoPresence(),
        )
    }

    @Test
    fun `only settled-on-audio ever ends fullscreen`() {
        // Stated as its own claim because it is the contract the player relies on, and because a
        // future third state must not silently start ending fullscreen by accident.
        val enders = VideoPresence.entries.filter { it == VideoPresence.SETTLED_ON_AUDIO }

        assertEquals(listOf(VideoPresence.SETTLED_ON_AUDIO), enders)
    }

    @Test
    fun `the description carries every input the decision was made from`() {
        // A verdict with no inputs cannot be re-judged from a report months later, which is
        // exactly why 0.1.374 had to be diagnosed by reading code instead.
        val described = state(hasVideo = false, isBuffering = false, isPlaying = false)
            .let { it.videoPresence().describe(it) }

        listOf("hasVideo=false", "buffering=false", "playing=false", "kind=VIDEO", "item=abc123")
            .forEach { assertTrue(it, described.contains(it)) }
    }

    private fun assertTrue(what: String, condition: Boolean) =
        org.junit.Assert.assertTrue("the log line must name $what", condition)

    private fun state(
        hasVideo: Boolean = false,
        isBuffering: Boolean = false,
        isPlaying: Boolean = false,
        kind: MediaKind = MediaKind.VIDEO,
    ) = PlaybackState(
        itemId = MediaItemId("abc123"),
        title = "t",
        artist = null,
        artworkUrl = null,
        kind = kind,
        isPlaying = isPlaying,
        positionMs = 0,
        durationMs = 1_000,
        speed = 1f,
        hasVideo = hasVideo,
        isBuffering = isBuffering,
    )
}
