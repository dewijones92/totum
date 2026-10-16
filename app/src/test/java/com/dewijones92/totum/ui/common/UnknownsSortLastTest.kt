package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class UnknownsSortLastTest {

    private fun item(id: String, at: Instant? = null, length: Duration? = null, by: String? = null) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = id,
        publishedAt = at,
        duration = length,
        author = by,
        mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
    )

    private fun List<MediaItem>.ids() = map { it.id.value }

    @Test
    fun `an item with no date, length or channel sorts after the ones that have one, in every order`() {
        val dated = item("known", Instant.parse("2026-10-01T00:00:00Z"), 5.minutes, "Novara")
        val unknown = item("unknown")
        val both = listOf(unknown, dated)

        for (order in listOf(
            MediaSort.NEWEST,
            MediaSort.OLDEST,
            MediaSort.LONGEST,
            MediaSort.SHORTEST,
            MediaSort.CHANNEL
        )) {
            assertEquals("$order", listOf("known", "unknown"), order.apply(both).ids())
        }
    }
}
