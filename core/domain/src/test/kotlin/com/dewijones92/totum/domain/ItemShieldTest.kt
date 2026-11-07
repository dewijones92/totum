package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class ItemShieldTest {

    private val item = MediaItem(
        id = MediaItemId("a"),
        sourceId = SourceId("s"),
        title = "Alcaraz beats Sinner",
        publishedAt = null,
        duration = 120.minutes,
        thumbnailUrl = HttpUrl.parse("https://img.example/a.jpg"),
    )

    @Test
    fun `no shield shows the item as it is`() {
        val face = NoShield.face(item)
        assertEquals("Alcaraz beats Sinner", face.title)
        assertEquals(120.minutes, face.duration)
        assertFalse(face.shielded)
        assertFalse(face.blurThumbnail)
        assertEquals(item.thumbnailUrl, face.artworkUrl)
        assertEquals("Alcaraz beats Sinner", NoShield.titleOf(item))
    }

    @Test
    fun `a shielded face has no artwork for places that cannot blur`() {
        val face = ItemFace("hidden", item.thumbnailUrl, null, blurThumbnail = true, shielded = true)
        assertNull(face.artworkUrl)
    }
}
