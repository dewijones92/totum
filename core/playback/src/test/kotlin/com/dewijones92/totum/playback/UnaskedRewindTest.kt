package com.dewijones92.totum.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnaskedRewindTest {

    private fun jump(
        reason: Int = Player.DISCONTINUITY_REASON_INTERNAL,
        fromMs: Long = 618_306,
        toMs: Long = 0,
        sameItem: Boolean = true,
        live: Boolean = false,
    ) = UnaskedRewind.isOne(reason, fromMs, toMs, sameItem, live)

    @Test
    fun `the player jumping to the start of the same item by itself is put back`() {
        assertTrue(jump())
        assertTrue(jump(fromMs = 1_183_063, toMs = 0))
    }

    @Test
    fun `a seek, a new item, live or a jump that is not to the start are left alone`() {
        assertFalse("asked for, by the person or the app", jump(reason = Player.DISCONTINUITY_REASON_SEEK))
        assertFalse("auto-advance", jump(reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
        assertFalse("another item", jump(sameItem = false))
        assertFalse("a live window moves on its own", jump(live = true))
        assertFalse("barely started, nothing to lose", jump(fromMs = 3_000))
        assertFalse("not back to the start", jump(toMs = 300_000))
    }
}
