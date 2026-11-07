package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.ItemFace
import com.dewijones92.totum.domain.ItemShield
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayAttemptsShieldTest {

    private val shield = object : ItemShield {
        override fun face(item: MediaItem): ItemFace = if (item.title.contains("beats")) {
            ItemFace("🙈 Title hidden", item.thumbnailUrl, null, blurThumbnail = true, shielded = true)
        } else {
            ItemFace(item.title, item.thumbnailUrl, item.duration)
        }
    }

    private fun item(title: String) = MediaItem(
        MediaItemId("v1"),
        SourceId("feed"),
        title,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://example.com/v1.mp4"),
    )

    @Test
    fun `the starting bar names a shielded item by its safe title`() {
        val attempts = PlayAttempts(shield) { 10L }

        val starting = attempts.begin(item("Alcaraz beats Sinner"))

        assertEquals("🙈 Title hidden", starting.title)
        assertEquals("🙈 Title hidden", attempts.starting.value?.title)
    }

    @Test
    fun `an item the shield lets through is named as it is`() {
        val starting = PlayAttempts(shield) { 10L }.begin(item("Practice session"))

        assertEquals("Practice session", starting.title)
    }

    @Test
    fun `a refused tap names a shielded item by its safe title, in the toast and the log`() = runTest {
        val attempts = PlayAttempts(shield) { 10L }
        val seen = mutableListOf<RefusedTap>()
        val job = attempts.taps.onEach { seen += it }.launchIn(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        attempts.announce(item("Alcaraz beats Sinner"))

        job.cancel()
        assertEquals(listOf("🙈 Title hidden"), seen.map { it.title })
        assertFalse(seen.single().title.contains("Alcaraz"))
    }

    @Test
    fun `ending the attempt clears the bar, and a late end of an older attempt does not clear a newer one`() {
        val attempts = PlayAttempts(shield) { 10L }
        val first = attempts.begin(item("Practice session"))
        val second = attempts.begin(item("Draw ceremony"))

        attempts.end(first, played = true)
        assertEquals("Draw ceremony", attempts.starting.value?.title)

        attempts.end(second, played = true)
        assertEquals(null, attempts.starting.value)
    }
}
