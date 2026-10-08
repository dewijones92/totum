package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaplessArmerTest {

    private var next: PlayableItem? = video("b")
    private var armed: MediaItemId? = null
    private var notNow: String? = null
    private var metered = false
    private val arms = mutableListOf<Pair<String, Boolean>>()
    private val disarms = mutableListOf<String>()

    private fun TestScope.armer() = GaplessArmer(
        states = MutableStateFlow(null),
        line = object : GaplessLine {
            override fun nextUp() = next
            override fun armedNext() = armed
            override fun notNow() = notNow
            override fun metered() = metered
            override suspend fun arm(allowStream: Boolean): Boolean {
                arms += next!!.item.id.value to allowStream
                armed = next!!.item.id
                return true
            }
            override fun disarm(reason: String) {
                disarms += reason
                armed = null
            }
        },
        scope = backgroundScope,
    )

    @Test
    fun `nothing is put in line early in an item`() = runTest {
        armer().consider(playing(positionMs = 0))

        assertEquals(emptyList<Pair<String, Boolean>>(), arms)
    }

    @Test
    fun `the next item is put in line once, in the last 45 seconds`() = runTest {
        val armer = armer()
        repeat(3) { armer.consider(playing(positionMs = 560_000L + it * 1_000)) }

        assertEquals(listOf("b" to true), arms)
    }

    @Test
    fun `on metered data the queue is told streams are not allowed`() = runTest {
        metered = true
        armer().consider(playing(positionMs = 580_000))

        assertEquals(listOf("b" to false), arms)
    }

    @Test
    fun `a reason not to arm takes back what was armed`() = runTest {
        val armer = armer()
        armer.consider(playing(positionMs = 580_000))

        notNow = "auto-play next is off"
        armer.consider(playing(positionMs = 581_000))

        assertEquals(listOf("auto-play next is off"), disarms)
        assertNull(armed)
    }

    @Test
    fun `when the queue's next changes, the old one is taken back and the new one put in line`() = runTest {
        val armer = armer()
        armer.consider(playing(positionMs = 580_000))

        next = video("c")
        armer.consider(playing(positionMs = 581_000))

        assertEquals(listOf("the queue's next is now c"), disarms)
        assertEquals(listOf("b" to true, "c" to true), arms)
    }

    @Test
    fun `the item already playing is never put in line behind itself`() = runTest {
        next = video("a")
        armer().consider(playing(positionMs = 580_000))

        assertEquals(emptyList<Pair<String, Boolean>>(), arms)
    }

    @Test
    fun `nothing is put in line while the playing item is still starting`() = runTest {
        val armer = armer()
        armer.consider(playing(positionMs = 0, durationMs = 19_000, bufferedMs = 1_000))
        armer.consider(playing(positionMs = 0, durationMs = 19_000, buffering = true, bufferedMs = 19_000))

        assertEquals(emptyList<Pair<String, Boolean>>(), arms)

        armer.consider(playing(positionMs = 2_000, durationMs = 19_000, bufferedMs = 19_000))
        assertEquals(listOf("b" to true), arms)
    }

    @Test
    fun `an item with no known length is never armed`() = runTest {
        armer().consider(playing(positionMs = 580_000, durationMs = null))

        assertEquals(emptyList<Pair<String, Boolean>>(), arms)
    }

    @Test
    fun `the reasons not to arm say which setting is in the way`() {
        val on = AppPreferences.Settings()
        assertNull(gaplessNotNow(on, SleepTimerState.Off))
        assertEquals(
            "gapless queue is off in Settings",
            gaplessNotNow(on.copy(gaplessQueue = false), SleepTimerState.Off)
        )
        assertEquals("auto-play next is off", gaplessNotNow(on.copy(autoPlayNext = false), SleepTimerState.Off))
        assertEquals("the sleep timer stops after this item", gaplessNotNow(on, SleepTimerState.AfterCurrentItem))
    }

    private fun playing(
        positionMs: Long,
        durationMs: Long? = 600_000,
        buffering: Boolean = false,
        bufferedMs: Long = (durationMs ?: positionMs),
    ) = PlaybackState(
        itemId = MediaItemId("a"),
        title = "a",
        artist = null,
        artworkUrl = null,
        isPlaying = !buffering,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = 1f,
        isBuffering = buffering,
        bufferedPositionMs = bufferedMs,
    )

    private fun video(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )
}
