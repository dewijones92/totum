package com.dewijones92.totum.playback

import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.fake.FakePlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FakePlaybackControllerTest {

    private val controller = FakePlaybackController()
    private val episode = FakePodcastRepository.sampleEpisode(SourceId("feed-1"))

    @Test
    fun `starts with no state`() {
        assertNull(controller.state.value)
    }

    @Test
    fun `play exposes the item as playing`() {
        controller.play(episode)

        val state = checkNotNull(controller.state.value)
        assertEquals(episode.id, state.itemId)
        assertEquals(episode.title, state.title)
        assertEquals(true, state.isPlaying)
        assertEquals(episode.duration?.inWholeMilliseconds, state.durationMs)
    }

    @Test
    fun `toggle flips playing state`() {
        controller.play(episode)
        controller.togglePlayPause()
        assertEquals(false, controller.state.value?.isPlaying)
        controller.togglePlayPause()
        assertEquals(true, controller.state.value?.isPlaying)
    }

    @Test
    fun `play can be called while another thread records play reasons`() {
        val stop = AtomicBoolean(false)
        val writer = thread {
            while (!stop.get()) controller.setPlayReason(episode.id, PlayReason.RepeatOne)
        }
        try {
            repeat(20_000) { controller.play(episode) }
        } finally {
            stop.set(true)
            writer.join()
        }

        assertEquals(episode.id, controller.state.value?.itemId)
    }
}
