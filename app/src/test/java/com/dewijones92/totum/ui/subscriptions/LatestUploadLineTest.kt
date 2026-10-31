package com.dewijones92.totum.ui.subscriptions

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LatestUploadLineTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")
    private fun upload(at: Instant?, text: String? = null) = MediaItem(
        id = MediaItemId("v"),
        sourceId = SourceId("s"),
        title = "24 Hours In An Abandoned Soviet City",
        publishedAt = at,
        publishedText = text,
        duration = null,
    )

    @Test
    fun `the latest upload is named however its date is known`() {
        assertEquals(
            "📅 2 hours ago · 24 Hours In An Abandoned Soviet City",
            latestUploadLine(upload(Instant.parse("2026-10-31T10:00:00Z")), now),
        )
        assertEquals(
            "📅 3 days ago · 24 Hours In An Abandoned Soviet City",
            latestUploadLine(upload(null, "3 days ago"), now)
        )
        assertEquals("24 Hours In An Abandoned Soviet City", latestUploadLine(upload(null), now))
        assertEquals(null, latestUploadLine(null, now))
    }
}
