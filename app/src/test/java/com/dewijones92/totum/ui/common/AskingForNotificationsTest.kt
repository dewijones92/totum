package com.dewijones92.totum.ui.common

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a launch may ask for the notification permission.
 *
 * Three predicates have been tried here and the first two were wrong in opposite directions, with
 * no test either time. Each of those is a case below, so a fourth attempt has to face them.
 */
class AskingForNotificationsTest {

    @Test
    fun `a quiet launch asks`() {
        assertTrue(mayAskForNotifications(hasSharedLink = false, state = null))
    }

    @Test
    fun `a launch carrying a shared link does not ask`() {
        assertFalse(
            "the dialog would open over the very video this launch exists to play",
            mayAskForNotifications(hasSharedLink = true, state = null),
        )
    }

    @Test
    fun `a launch while something is playing does not ask`() {
        // isPlaying implies playWhenReady in Media3 (READY && playWhenReady && no suppression), so
        // a fixture with isPlaying true and wantsToPlay false is a state that cannot occur.
        assertFalse(
            mayAskForNotifications(
                hasSharedLink = false,
                state = state(isPlaying = true, wantsToPlay = true),
            ),
        )
    }

    /**
     * THE `isPlaying`-ONLY BUG. Media3 reports `isPlaying` false while BUFFERING even with
     * playWhenReady true — this repo says so beside `togglePlayPause` — so a shared link still
     * spinning up would have been asked over.
     */
    @Test
    fun `a launch while a video is still buffering does not ask`() {
        assertFalse(
            "a video that is spinning up is exactly the window this gate exists for",
            mayAskForNotifications(
                hasSharedLink = false,
                state = state(isBuffering = true, wantsToPlay = true),
            ),
        )
    }

    /**
     * THE `state != null` AND `wantsToPlay` BUG, which is the same bug twice. Neither clears when
     * an item ends — nothing calls pause() but the user — so once anything had played, the ask was
     * skipped for the rest of the process's life.
     */
    @Test
    fun `a launch after an item has ended still asks`() {
        assertTrue(
            "an ended item must not suppress the ask for the rest of the process",
            mayAskForNotifications(
                hasSharedLink = false,
                state = state(isPlaying = false, isBuffering = false, wantsToPlay = true, hasEnded = true),
            ),
        )
    }

    /**
     * THE `isPlaying || isBuffering` BUG — the third wrong answer, which dropped a case the second
     * one covered.
     *
     * `PlaybackService` passes `handleAudioFocus = true`, so a transient focus loss suppresses
     * playback: Media3 reports READY and playWhenReady but `isPlaying` false, and the player is not
     * buffering either. The video is on screen and about to resume, and the dialog would land on it.
     */
    @Test
    fun `a launch while playback is suppressed by audio focus does not ask`() {
        assertFalse(
            "a suppressed video is still on screen and still about to play",
            mayAskForNotifications(
                hasSharedLink = false,
                state = state(isPlaying = false, isBuffering = false, wantsToPlay = true),
            ),
        )
    }

    private fun state(
        isPlaying: Boolean = false,
        isBuffering: Boolean = false,
        wantsToPlay: Boolean = false,
        hasEnded: Boolean = false,
    ) = PlaybackState(
        itemId = MediaItemId("probe"),
        title = "an item",
        artist = null,
        artworkUrl = null,
        isPlaying = isPlaying,
        positionMs = 0,
        durationMs = null,
        speed = 1f,
        isBuffering = isBuffering,
        wantsToPlay = wantsToPlay,
        hasEnded = hasEnded,
    )
}
