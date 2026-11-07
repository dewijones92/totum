package com.dewijones92.totum.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.domain.ItemFace
import com.dewijones92.totum.domain.ItemShield
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.live.LiveNoticeLabels
import com.dewijones92.totum.ui.common.shareListMessage
import com.dewijones92.totum.video.LiveStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerShieldSurfacesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val shield = object : ItemShield {
        override fun face(item: MediaItem): ItemFace = if (item.title.contains("beats")) {
            ItemFace("🙈 Title hidden", item.thumbnailUrl, null, blurThumbnail = true, shielded = true)
        } else {
            ItemFace(item.title, item.thumbnailUrl, item.duration)
        }
    }

    private fun item(id: String, title: String, kind: MediaContentKind = MediaContentKind.STANDARD) = MediaItem(
        MediaItemId(id),
        SourceId("feed"),
        title,
        publishedAt = null,
        duration = null,
        contentKind = kind,
    )

    @Test
    fun aSharedListNamesAShieldedItemBySafeTitleAndTheRestAsTheyAre() {
        val items = listOf(item("a", "Alcaraz beats Sinner"), item("b", "Practice session"))

        val message = context.resources.shareListMessage("Tennis", items) { shield.face(it).title }

        assertEquals("Tennis\n1. 🙈 Title hidden\n2. Practice session", message)
        assertFalse(message.contains("Alcaraz"))
    }

    @Test
    fun aSharedListWithNoShieldUsesRealTitles() {
        val message = context.resources.shareListMessage("Tennis", listOf(item("a", "Alcaraz beats Sinner")))

        assertTrue(message, message.contains("1. Alcaraz beats Sinner"))
    }

    @Test
    fun aNewVideoNoticeNamesAShieldedItemBySafeTitle() {
        val labels = LiveNoticeLabels(context, LiveStates(), shield)

        assertEquals("🙈 Title hidden", labels.titled(item("v1", "Alcaraz beats Sinner")))
        assertEquals("Practice session", labels.titled(item("v2", "Practice session")))
    }

    @Test
    fun aLiveItemNeverLetsItsRealTitleThroughBesideItsTag() {
        val labels = LiveNoticeLabels(context, LiveStates(), shield)

        val titled = labels.titled(item("aaaaaaaaaa1", "Alcaraz beats Sinner", MediaContentKind.LIVE))

        assertTrue(titled, titled.endsWith("🙈 Title hidden"))
        assertFalse(titled.contains("Alcaraz"))
    }
}
