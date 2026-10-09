package com.dewijones92.totum.ui.player

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimer
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndOfGroupSleepTest {

    private val album = QueueGroup("album:x", "Guitar Recital")
    private val controller = FakePlaybackController()
    private val timer = SleepTimer(controller, CoroutineScope(Dispatchers.Unconfined))

    private fun entry(id: String, group: QueueGroup? = album) = QueueEntry(
        PlayableItem(
            MediaItem(MediaItemId(id), SourceId("s"), id, publishedAt = null, duration = null),
            PlayHandle.Podcast()
        ),
        group,
    )

    @Test
    fun `the rest of the album runs from the song playing to the album's last song`() {
        val entries = listOf(entry("t1"), entry("t2"), entry("t3"), entry("t4"), entry("other", null))

        val sleep = endOfGroupSleep(entries, currentIndex = 1, timer = timer)

        assertEquals("Guitar Recital", sleep?.title)
        assertEquals(3, sleep?.songs)
    }

    @Test
    fun `the last song of an album, or something outside one, offers no end-of-album stop`() {
        assertNull(endOfGroupSleep(listOf(entry("t1"), entry("t2")), currentIndex = 1, timer = timer))
        assertNull(endOfGroupSleep(listOf(entry("solo", null), entry("t1")), currentIndex = 0, timer = timer))
    }

    @Test
    fun `starting it arms the timer on the album's last song`() {
        val entries = listOf(entry("t1"), entry("t2"), entry("t3"))
        controller.emitState(
            PlaybackState(
                MediaItemId("t1"),
                "t1",
                null,
                artworkUrl = null,
                isPlaying = true,
                positionMs = 0,
                durationMs = null,
                speed = 1f
            ),
        )

        endOfGroupSleep(entries, currentIndex = 0, timer = timer)?.start?.invoke()

        assertEquals(MediaItemId("t3"), timer.stopsAfterItem)
        timer.cancel()
        assertEquals(SleepTimerState.Off, timer.state.value)
    }
}
