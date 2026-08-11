package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The speed the UI shows is the speed the USER chose — never the one the player happens to be at.
 *
 * The two are genuinely different numbers whenever skip-silence is racing through dead air, and
 * reporting the wrong one is what made the speed button flick to "4x" and back throughout any
 * video with silence in it (Dewi, 2026-08-09: *"the speed of the video isn't maintained"*).
 *
 * Two tests, because the claim has two halves and only one of them is arithmetic:
 *
 * - that the two numbers ARE different while racing, so reporting the player's is wrong ([racing]);
 * - and that the state mapping reads the user's, which is a fact about a line of source and cannot
 *   be observed without a real MediaController and a real silent stretch. Guarding it at the source
 *   costs nothing and fails the moment someone puts `playbackParameters.speed` back — which is
 *   exactly how it got there in the first place. Same approach as `UnifiedRowArchitectureTest`.
 */
class ReportedSpeedTest {

    @Test
    fun `while racing, what the player does and what the user asked for are different numbers`() {
        val racer = SilenceRacer()
        racer.userChose(CHOSEN)

        racer.silence(silent = true)

        assertEquals("the player really is racing", CHOSEN * SilenceRacer.SILENCE_MULTIPLIER, racer.speed, EXACT)
        assertEquals("and this is the only one worth showing anybody", CHOSEN, racer.userSpeed, EXACT)
    }

    @Test
    fun `the state mapping reports the chosen speed, not the player's`() {
        val controller = File(SOURCE).readText()

        assertTrue(
            "$SOURCE must build PlaybackState.speed from the user's chosen rate. Found no " +
                "`speed = userSpeed`, which means the speed button will read whatever skip-silence " +
                "is currently racing at.",
            controller.contains("speed = userSpeed"),
        )
        assertTrue(
            "$SOURCE assigns PlaybackState.speed from playbackParameters, which is the racing " +
                "rate whenever a silent stretch is in progress. Use the user's chosen rate.",
            !controller.contains("speed = playbackParameters.speed"),
        )
    }

    @Test
    fun `the chosen speed is re-applied to every item, so the queue cannot forget it`() {
        val controller = File(SOURCE).readText()

        // The rate is a setting, and settings survive the next video. It is applied inside play()
        // rather than once at startup, which is the only reason an auto-advance keeps it.
        assertTrue(
            "$SOURCE must apply the user's speed as part of play(), or the next item in the " +
                "queue starts at whatever the player was left at.",
            controller.contains("applyUserSpeed(controller, speed)"),
        )
    }

    @Test
    fun `the service is TOLD the chosen speed rather than guessing it from the player`() {
        val service = File(SERVICE).readText()

        assertTrue(
            "$SERVICE must handle ACTION_USER_SPEED. Without it the silence racer has to infer " +
                "the user's rate from the player's own callback, which is how a rate chosen " +
                "mid-silence used to be thrown away.",
            service.contains("ACTION_USER_SPEED"),
        )
        assertTrue(
            "$SERVICE must not infer the user's rate from onPlaybackParametersChanged — that " +
                "callback also fires for the racing rate this class set itself.",
            !service.contains("userSpeed = playbackParameters.speed"),
        )
    }

    private companion object {
        const val CHOSEN = 1.5f
        const val EXACT = 0f
        const val SOURCE = "src/main/kotlin/com/dewijones92/totum/playback/Media3PlaybackController.kt"
        const val SERVICE = "src/main/kotlin/com/dewijones92/totum/playback/PlaybackService.kt"
    }
}
