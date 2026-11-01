package com.dewijones92.totum.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderTitleTest {

    private fun item(title: String) = MediaItem(
        MediaItemId("C6pdB9oikdo"),
        SourceId("s"),
        title,
        publishedAt = null,
        duration = null
    )

    @Test
    fun `a title that is only the video id is a placeholder, as is the shared-link placeholder`() {
        assertTrue(item("C6pdB9oikdo").hasPlaceholderTitle)
        assertTrue(item(placeholderTitleFor(MediaItemId("C6pdB9oikdo"))).hasPlaceholderTitle)
        assertFalse(item("World Domination, explained").hasPlaceholderTitle)
    }
}
