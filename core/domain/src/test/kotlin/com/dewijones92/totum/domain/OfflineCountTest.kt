package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCountTest {

    private val ids = listOf("a", "b", "c", "d").map(::MediaItemId)

    @Test
    fun `counts finished and running downloads among the items, ignoring everything else`() {
        val states = mapOf(
            MediaItemId("a") to DownloadState.Downloaded("/a", audioOnly = true),
            MediaItemId("b") to DownloadState.Downloading(10, 100),
            MediaItemId("z") to DownloadState.Downloaded("/z"),
        )

        val count = offlineCount(ids, states)

        assertEquals(OfflineCount(total = 4, downloaded = 1, inProgress = 1), count)
        assertFalse(count.complete)
    }

    @Test
    fun `everything downloaded is complete`() {
        val states = ids.associateWith { DownloadState.Downloaded("/${it.value}") }

        assertTrue(offlineCount(ids, states).complete)
    }

    @Test
    fun `nothing at all is not complete`() {
        assertFalse(offlineCount(emptyList(), emptyMap()).complete)
    }
}
