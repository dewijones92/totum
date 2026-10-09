package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.RadioBatch
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue.Companion.TOKEN
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicRadioTest {

    private fun song(id: String) = MusicSong(
        videoId = id,
        title = "song $id",
        artist = "someone",
        album = null,
        durationSeconds = 180,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    private fun batch(prefix: String, count: Int, next: Int?) =
        RadioBatch((1..count).map { song("$prefix$it") }, next?.let { "$TOKEN$it" })

    private class Rig(scope: TestScope, catalogue: FakeYouTubeMusicCatalogue) {
        val work = CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob())
        val controller = FakePlaybackController()
        val queue = PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = work,
        )
        val radio = MusicRadio(catalogue, queue, work)

        fun radioIds(): List<String> =
            queue.state.value.entries.filter { it.group?.id == radio.activeGroupId }.map { it.item.item.id.value }
    }

    @Test
    fun `starting a radio queues its first batch as music, inserted after what is playing`() = runTest {
        val catalogue = FakeYouTubeMusicCatalogue(radioBatches = listOf(batch("a", 10, next = 1)))
        val rig = Rig(this, catalogue)

        val started = rig.radio.start(RadioSeed.forSong("seed"), "Feeling Good")
        advanceUntilIdle()

        assertTrue(started)
        val entries = rig.queue.state.value.entries
        assertEquals(10, entries.size)
        assertTrue(entries.all { it.item.item.contentKind == MediaContentKind.MUSIC })
        assertEquals("Radio · Feeling Good", entries.first().group?.title)
        rig.work.cancel()
    }

    @Test
    fun `nearing the end of the radio fetches the next batch and appends it to the radio`() = runTest {
        val catalogue = FakeYouTubeMusicCatalogue(
            radioBatches = listOf(batch("a", 4, next = 1), batch("b", 5, next = 2)),
        )
        val rig = Rig(this, catalogue)
        rig.radio.start(RadioSeed.forSong("seed"), "Feeling Good")
        advanceUntilIdle()

        rig.queue.jumpTo(1)
        advanceUntilIdle()

        assertEquals(9, rig.radioIds().size)
        assertEquals(
            listOf(RadioSeed.forSong("seed") to null, RadioSeed.forSong("seed") to "${TOKEN}1"),
            catalogue.radioRequests
        )
        rig.work.cancel()
    }

    @Test
    fun `plenty left ahead means no fetch`() = runTest {
        val catalogue = FakeYouTubeMusicCatalogue(
            radioBatches = listOf(batch("a", 20, next = 1), batch("b", 5, next = 2)),
        )
        val rig = Rig(this, catalogue)
        rig.radio.start(RadioSeed.forSong("seed"), "Feeling Good")
        advanceUntilIdle()

        rig.queue.jumpTo(2)
        advanceUntilIdle()

        assertEquals(1, catalogue.radioRequests.size)
        rig.work.cancel()
    }

    @Test
    fun `songs the radio already offered are not offered again`() = runTest {
        val repeat = RadioBatch(listOf(song("a1"), song("a2"), song("c1")), "${TOKEN}2")
        val catalogue = FakeYouTubeMusicCatalogue(radioBatches = listOf(batch("a", 3, next = 1), repeat))
        val rig = Rig(this, catalogue)
        rig.radio.start(RadioSeed.forSong("seed"), "Feeling Good")
        advanceUntilIdle()
        rig.queue.remove(rig.queue.state.value.entries.first { it.item.item.id.value.endsWith("a3") })

        rig.queue.jumpTo(0)
        advanceUntilIdle()

        val ids = rig.radioIds().map { it.substringAfter("v=") }
        assertEquals(listOf("a1", "a2", "c1"), ids)
        rig.work.cancel()
    }

    @Test
    fun `playing something outside the radio stops it topping up`() = runTest {
        val catalogue = FakeYouTubeMusicCatalogue(
            radioBatches = listOf(batch("a", 10, next = 1), batch("b", 5, next = 2)),
        )
        val rig = Rig(this, catalogue)
        rig.radio.start(RadioSeed.forSong("seed"), "Feeling Good")
        advanceUntilIdle()
        rig.queue.enqueue(song("other").toPlayable(MusicSources.radio("x")))
        advanceUntilIdle()

        rig.queue.jumpTo(rig.queue.state.value.entries.lastIndex)
        advanceUntilIdle()

        assertEquals(1, catalogue.radioRequests.size)
        assertFalse(rig.radio.isActive)
        rig.work.cancel()
    }

    @Test
    fun `a radio that YouTube cannot start says so and queues nothing`() = runTest {
        val rig = Rig(this, FakeYouTubeMusicCatalogue(radioBatches = emptyList()))

        val started = rig.radio.start(RadioSeed.forSong("seed"), "Feeling Good")
        advanceUntilIdle()

        assertFalse(started)
        assertTrue(rig.queue.state.value.entries.isEmpty())
        rig.work.cancel()
    }
}
