package com.dewijones92.totum.ui.common

import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The sheet's "go to" action is named and drawn per pillar, from one rule, not per caller. */
class GoToSourceLabelTest {

    @Test
    fun `a video goes to its channel and an episode to its podcast`() {
        assertEquals(R.string.go_to_channel, goToSourceLabelRes(MediaKind.VIDEO))
        assertEquals(R.string.go_to_podcast, goToSourceLabelRes(MediaKind.PODCAST))
    }

    @Test
    fun `the glyph follows the pillar too, so the two can never disagree`() {
        assertEquals(pillarIcon(MediaKind.VIDEO), pillarIcon(MediaKind.VIDEO))
        assertNotEquals(pillarIcon(MediaKind.VIDEO), pillarIcon(MediaKind.PODCAST))
    }
}
