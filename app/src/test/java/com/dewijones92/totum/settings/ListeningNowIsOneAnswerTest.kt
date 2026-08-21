package com.dewijones92.totum.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Am I listening?" has one answer, and the rows were getting a different one from playback.
 *
 * [PlaybackMode.AUTO] is the shipped default and means "video on Wi-Fi, audio on mobile data", so it
 * cannot be answered without knowing the network. Playback resolved it properly;
 * `MediaItemActions.audioMode` and `ShortsReelScreen` each hand-rolled `mode == AUDIO`, which is false
 * on AUTO however metered the connection is.
 *
 * So on 4G with defaults the app played audio while every row's sheet offered "Listen only" with a
 * headphones glyph and never offered "Watch with video" — the label saying the opposite of what was
 * happening, and no one-tap way to ask for the picture back.
 */
class ListeningNowIsOneAnswerTest {

    /** THE case the hand-rolled copies got wrong. */
    @Test
    fun `auto on a metered network is listening`() {
        assertTrue(
            "AUTO means audio on mobile data — this is the default mode, so a row that reads it as " +
                "\"not listening\" mislabels itself for anyone on 4G",
            listeningIn(PlaybackMode.AUTO, onMeteredNetwork = true),
        )
    }

    @Test
    fun `auto on wifi is watching`() {
        assertFalse(listeningIn(PlaybackMode.AUTO, onMeteredNetwork = false))
    }

    /** The explicit modes must ignore the network, or a setting would not be a setting. */
    @Test
    fun `audio is listening on any network`() {
        assertTrue(listeningIn(PlaybackMode.AUDIO, onMeteredNetwork = false))
        assertTrue(listeningIn(PlaybackMode.AUDIO, onMeteredNetwork = true))
    }

    @Test
    fun `video is watching on any network`() {
        assertFalse(listeningIn(PlaybackMode.VIDEO, onMeteredNetwork = false))
        assertFalse(listeningIn(PlaybackMode.VIDEO, onMeteredNetwork = true))
    }
}
