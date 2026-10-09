package com.dewijones92.totum.pins

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.RadioBatch
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.innertube.playlists.fake.FakeYouTubePlaylists
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PinsTest {

    private val trainVideo = PlayableItem(
        MediaItem(
            id = MediaItemId("trainVideo1"),
            sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
            title = "Trains with Ms Rachel",
            publishedAt = null,
            duration = null,
            thumbnailUrl = HttpUrl.of("https://i.ytimg.com/vi/trainVideo1/hq.jpg"),
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=trainVideo1")),
    )

    private val everyKind = listOf(
        Pin.of(trainVideo),
        Pin.Album("MPREb_x", "Guitar Recital", "https://art/x"),
        Pin.Playlist("VLLM", "Liked Music", null, music = true),
        Pin.Artist(null, "Pablo Sáinz-Villegas", null, radioPlaylistId = "RDEMabc"),
        Pin.Radio("BNMKGYiJpvg", "RDAMVMBNMKGYiJpvg", "Feeling Good", null),
        Pin.Show("feed-1", "https://feed.test/rss", "A show", null),
    )

    @Test
    fun `every kind of pin survives being written and read back`() {
        assertEquals(everyKind, PinCodec.decode(PinCodec.encode(everyKind)))
    }

    @Test
    fun `a pinned item comes back as the same playable item`() {
        val back = (PinCodec.decode(PinCodec.encode(listOf(Pin.of(trainVideo)))).single() as Pin.Item).playable()

        assertEquals(trainVideo.item.id, back?.item?.id)
        assertEquals(trainVideo.handle, back?.handle)
        assertEquals(trainVideo.item.title, back?.item?.title)
    }

    @Test
    fun `pinning the same thing twice keeps one, newest last, and unpin removes it`() {
        val store = InMemoryPinStore()
        store.pin(everyKind[1])
        store.pin(everyKind[0])
        store.pin(everyKind[1])

        assertEquals(listOf(everyKind[0].key, everyKind[1].key), store.pins.value.map { it.key })
        store.unpin(everyKind[0].key)
        assertEquals(listOf(everyKind[1].key), store.pins.value.map { it.key })
    }

    @Test
    fun `a video pinned under its watch URL is the same pin as its bare id, and its old icon still finds it`() {
        val watch = "https://www.youtube.com/watch?v=trainVideo1"
        val old = Pin.of(trainVideo.copy(item = trainVideo.item.copy(id = MediaItemId(watch))))
        val store = InMemoryPinStore()
        store.pin(old)

        store.pin(Pin.of(trainVideo))

        assertEquals(1, store.pins.value.size)
        assertEquals(Pin.of(trainVideo).key, store.find("item:$watch")?.key)
        assertEquals(MediaItemId("trainVideo1"), (store.pins.value.single() as Pin.Item).playable()?.item?.id)
    }

    private class Resolves : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult = ExtractionResult.Success(
            MediaMetadata(
                id = url.value.substringAfter("v="),
                title = "resolved",
                uploader = null,
                durationSeconds = 120,
                thumbnailUrl = null,
                formats = listOf(
                    MediaFormat("18", "mp4", 640, 360, true, true, 500_000, "https://x.test/v", "avc1", "mp4a"),
                ),
            ),
        )
    }

    private class Rig(scope: TestScope, catalogue: FakeYouTubeMusicCatalogue, podcasts: FakePodcastRepository) {
        val work = CoroutineScope(scope.coroutineContext + SupervisorJob())
        val controller = FakePlaybackController()
        val queue = PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(Resolves(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = work,
        )
        val played = mutableSetOf<String>()
        val player = PinPlayer(
            queue,
            catalogue,
            FakeYouTubePlaylists(),
            MusicRadio(catalogue, queue, work),
            podcasts,
            playState = { id -> if (id.value in played) PlayState.Played else PlayState.Unplayed },
        )
    }

    private fun song(id: String) = MusicSong(
        id,
        "song $id",
        "someone",
        null,
        100,
        null,
        HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    @Test
    fun `a pinned album plays as a group, as music`() = runTest {
        val album = MusicAlbum(
            "MPREb_x", "Guitar Recital", null, null, null, MusicReleaseKind.ALBUM, null, null, null,
            "OLAK5uy_x", listOf(song("t1"), song("t2")),
        )
        val rig = Rig(this, FakeYouTubeMusicCatalogue(albumsById = mapOf("MPREb_x" to album)), FakePodcastRepository())

        val result = rig.player.play(Pin.Album("MPREb_x", "Guitar Recital", null), from = "test")
        advanceUntilIdle()

        assertTrue(result is PinPlayed.Started)
        val entries = rig.queue.state.value.entries
        assertEquals(listOf("t1", "t2"), entries.map { it.item.item.id.value })
        assertEquals("Guitar Recital", entries.first().group?.title)
        assertTrue(entries.all { it.item.item.contentKind == MediaContentKind.MUSIC })
        rig.work.cancel()
    }

    @Test
    fun `a pinned video says it shows a picture, so the shortcut can open the player`() = runTest {
        val rig = Rig(this, FakeYouTubeMusicCatalogue(), FakePodcastRepository())

        val result = rig.player.play(Pin.of(trainVideo), from = "test")
        advanceUntilIdle()

        assertEquals(trainVideo.item.id, rig.queue.state.value.current?.item?.item?.id)
        assertEquals(PinPlayed.Started(showsPicture = true), result)
        rig.work.cancel()
    }

    @Test
    fun `a pinned artist with no channel id plays its mix`() = runTest {
        val catalogue =
            FakeYouTubeMusicCatalogue(radioBatches = listOf(RadioBatch(listOf(song("m1"), song("m2")), null)))
        val rig = Rig(this, catalogue, FakePodcastRepository())

        rig.player.play(Pin.Artist(null, "Someone", null, radioPlaylistId = "RDEMabc"), from = "test")
        advanceUntilIdle()

        assertEquals("RDEMabc", catalogue.radioRequests.single().first.playlistId)
        assertEquals(2, rig.queue.state.value.entries.size)
        rig.work.cancel()
    }

    @Test
    fun `a pinned show plays its newest episode not yet played`() = runTest {
        fun episode(id: String, day: Long) = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed-1"),
            title = id,
            publishedAt = Instant.ofEpochSecond(day * 86_400),
            duration = null,
            mediaUrl = HttpUrl.of("https://feed.test/$id.mp3"),
        )
        val podcasts =
            FakePodcastRepository(
                initialEpisodes = listOf(episode("old", 1), episode("newest", 3), episode("middle", 2))
            )
        val rig = Rig(this, FakeYouTubeMusicCatalogue(), podcasts)
        rig.played += "newest"

        rig.player.play(Pin.Show("feed-1", "https://feed.test/rss", "A show", null), from = "test")
        advanceUntilIdle()

        assertEquals("middle", rig.queue.state.value.current?.item?.item?.id?.value)
        rig.work.cancel()
    }
}
