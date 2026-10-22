package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class AnUnreadableCopyIsNotANetworkFaultTest {

    @Test
    fun `a saved file the player cannot parse is an unreadable copy, not a dead network`() {
        assertEquals(
            StreamFailure.Reason.UnreadableCopy,
            recoveryReasonFrom(
                address = null,
                sabrStalled = false,
                unreachable = true,
                unparsable = Unparsable.LOCAL_FILE
            ),
        )
    }

    @Test
    fun `a stream the player cannot parse gets one fresh lookup rather than a wait for the network`() {
        assertEquals(
            StreamFailure.Reason.Rejected,
            recoveryReasonFrom(address = null, sabrStalled = false, unreachable = true, unparsable = Unparsable.STREAM),
        )
    }
}
