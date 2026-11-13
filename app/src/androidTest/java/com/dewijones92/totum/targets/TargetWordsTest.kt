package com.dewijones92.totum.targets

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TroubleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetWordsTest {

    private val words = ContextTargetWords(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun aSpeakerOutOfRangeIsNotConnected() {
        assertEquals(
            "📶 Couldn't reach Soundbar: it isn't connected (off or out of range)",
            words.trouble("Soundbar", TargetTrouble(TroubleKind.NOT_CONNECTED), null),
        )
    }

    @Test
    fun aSpeakerSwitchedOffAsksToSwitchItOn() {
        assertEquals(
            "📶 Switch Soundmic on, then try again",
            words.trouble("Soundmic", TargetTrouble(TroubleKind.SWITCH_ON), null),
        )
    }

    @Test
    fun aTvSwitchedOffMidItemSaysSo() {
        assertEquals("📺 LG TV was switched off", words.trouble("LG TV", TargetTrouble(TroubleKind.SWITCHED_OFF), "Bluey"))
    }

    @Test
    fun anItemThatCouldNotPlayNamesItAndTheReason() {
        assertEquals(
            "❌ Couldn't play “Bluey”: it is not on this Pi",
            words.trouble("LG TV", TargetTrouble(TroubleKind.COULD_NOT_PLAY, "a", "it is not on this Pi"), "Bluey"),
        )
    }

    @Test
    fun aBackOffSaysWhenToTryAgain() {
        val line = words.trouble("Soundbar", TargetTrouble(TroubleKind.YOUTUBE_BACKOFF, untilMs = 1_700_000_000_000), null)

        assertTrue(line, line.startsWith("⏳ YouTube is refusing the Pi for now; try again around "))
    }

    @Test
    fun theSignInLineIsTheKeyOne() {
        assertEquals("🔑 Sign in to the home server again", words.signInAgain())
    }
}
