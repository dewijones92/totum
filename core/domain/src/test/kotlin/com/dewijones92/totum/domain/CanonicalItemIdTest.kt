package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalItemIdTest {

    @Test
    fun `a watch url id becomes the video id`() {
        assertEquals("Nn_CcTBataE", canonicalItemId("https://www.youtube.com/watch?v=Nn_CcTBataE"))
    }

    @Test
    fun `anything else is left alone`() {
        assertEquals("Nn_CcTBataE", canonicalItemId("Nn_CcTBataE"))
        assertEquals("https://feed.test/ep1.mp3", canonicalItemId("https://feed.test/ep1.mp3"))
        assertEquals("torrent:abc:1", canonicalItemId("torrent:abc:1"))
        assertEquals(
            "https://www.youtube.com/watch?v=Nn_CcTBataE&list=x",
            canonicalItemId("https://www.youtube.com/watch?v=Nn_CcTBataE&list=x"),
        )
    }
}
