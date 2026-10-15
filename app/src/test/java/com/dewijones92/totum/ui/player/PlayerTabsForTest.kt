package com.dewijones92.totum.ui.player

import com.dewijones92.totum.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTabsForTest {

    @Test
    fun `the transcript tab shows only when there is a transcript, for videos and podcasts alike`() {
        assertEquals(
            listOf(PlayerTab.UP_NEXT, PlayerTab.RELATED, PlayerTab.COMMENTS, PlayerTab.DETAILS),
            playerTabsFor(MediaKind.VIDEO, hasTranscript = false),
        )
        assertEquals(
            listOf(PlayerTab.UP_NEXT, PlayerTab.RELATED, PlayerTab.COMMENTS, PlayerTab.TRANSCRIPT, PlayerTab.DETAILS),
            playerTabsFor(MediaKind.VIDEO, hasTranscript = true),
        )
        assertEquals(
            listOf(PlayerTab.UP_NEXT, PlayerTab.DETAILS),
            playerTabsFor(MediaKind.PODCAST, hasTranscript = false)
        )
        assertEquals(
            listOf(PlayerTab.UP_NEXT, PlayerTab.TRANSCRIPT, PlayerTab.DETAILS),
            playerTabsFor(MediaKind.PODCAST, hasTranscript = true),
        )
    }
}
