package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GaplessArmerOnACastTest {

    private val next = PlayableItem(
        MediaItem(
            id = MediaItemId("b"),
            sourceId = SourceId("s"),
            title = "b",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/b.mp3"),
        ),
        PlayHandle.Podcast(),
    )
    private var armed: MediaItemId? = null
    private val arms = mutableListOf<String>()

    private fun TestScope.armer() = GaplessArmer(
        states = MutableStateFlow(null),
        line = object : GaplessLine {
            override fun nextUp() = next
            override fun armedNext() = armed
            override fun notNow(): String? = null
            override fun metered() = false
            override suspend fun arm(allowStream: Boolean): Boolean {
                arms += next.item.id.value
                armed = next.item.id
                return true
            }

            override fun disarm(reason: String) {
                armed = null
            }
        },
        scope = backgroundScope,
    )

    private fun state(
        positionMs: Long,
        onCast: Boolean,
        playing: Boolean = true,
        buffering: Boolean = false,
        bufferedMs: Long = positionMs,
        ended: Boolean = false,
    ) = PlaybackState(
        itemId = MediaItemId("a"),
        title = "a",
        artist = null,
        artworkUrl = null,
        isPlaying = playing,
        positionMs = positionMs,
        durationMs = DURATION_MS,
        speed = 1f,
        isBuffering = buffering,
        bufferedPositionMs = bufferedMs,
        hasEnded = ended,
        onCastDevice = onCast,
    )

    @Test
    fun `on a cast the next item is not armed with ten minutes left`() = runTest {
        armer().consider(state(positionMs = 0, onCast = true))

        assertEquals(emptyList<String>(), arms)
    }

    @Test
    fun `on a cast it is not armed one second before the window opens`() = runTest {
        armer().consider(state(positionMs = DURATION_MS - GaplessArmer.LEAD_MS - 1_000, onCast = true))

        assertEquals(emptyList<String>(), arms)
    }

    @Test
    fun `on a cast it is armed inside the window while playing, though the receiver reports no buffer`() = runTest {
        val position = DURATION_MS - GaplessArmer.LEAD_MS + 1_000

        armer().consider(state(positionMs = position, onCast = true, bufferedMs = 0))

        assertEquals(listOf("b"), arms)
    }

    @Test
    fun `on a cast it is not armed inside the window while paused`() = runTest {
        val position = DURATION_MS - GaplessArmer.LEAD_MS + 1_000

        armer().consider(state(positionMs = position, onCast = true, playing = false))

        assertEquals(emptyList<String>(), arms)
    }

    @Test
    fun `on a cast it is not armed once the item has ended`() = runTest {
        armer().consider(state(positionMs = DURATION_MS - 1_000, onCast = true, ended = true))

        assertEquals(emptyList<String>(), arms)
    }

    @Test
    fun `on the phone the same moment with no buffer ahead is not armed, which is the difference a cast makes`() =
        runTest {
            val position = DURATION_MS - GaplessArmer.LEAD_MS + 1_000

            armer().consider(state(positionMs = position, onCast = false, bufferedMs = position))

            assertEquals(emptyList<String>(), arms)
        }

    @Test
    fun `on the phone with a buffer ahead it is armed`() = runTest {
        val position = DURATION_MS - GaplessArmer.LEAD_MS + 1_000

        armer().consider(state(positionMs = position, onCast = false, bufferedMs = DURATION_MS))

        assertEquals(listOf("b"), arms)
    }

    private companion object {
        const val DURATION_MS = 600_000L
    }
}
