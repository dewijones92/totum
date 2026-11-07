package com.dewijones92.totum.ui.common

import com.dewijones92.totum.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShieldableChannelNameTest {

    @Test
    fun `a YouTube video with a known channel can hide spoilers whether or not its row carries a channel id`() {
        assertEquals("jawed", shieldableChannelName("jawed", "UC123", false, MediaKind.VIDEO))
        assertEquals("jawed", shieldableChannelName("jawed", null, false, MediaKind.VIDEO))
    }

    @Test
    fun `a video with no channel name has nothing to hide`() {
        assertNull(shieldableChannelName(null, "UC123", false, MediaKind.VIDEO))
        assertNull(shieldableChannelName("  ", null, false, MediaKind.VIDEO))
    }

    @Test
    fun `a podcast episode is offered the switch only when its channel is already on`() {
        assertNull(shieldableChannelName("Show", null, false, MediaKind.PODCAST))
        assertEquals("Show", shieldableChannelName("Show", null, true, MediaKind.PODCAST))
    }
}
