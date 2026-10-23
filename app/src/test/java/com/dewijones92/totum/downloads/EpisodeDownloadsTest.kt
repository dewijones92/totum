package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDownloadsTest {

    private fun item(id: String, handle: PlayHandle) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.test/$id"),
        ),
        handle,
    )

    private fun video(id: String) = item(id, PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")))
    private fun torrentFile(id: String) =
        item(id, PlayHandle.Podcast(audioUrl = HttpUrl.of("https://home.example/ts/audio/h/1/index.m3u8")))

    @Test
    fun `only the ticked episodes download, at the chosen quality`() = runTest {
        val downloads = FakeDownloadManager()
        val episodes = EpisodeDownloads(downloads, this)
        val ask = EpisodeDownloads.Ask(
            "Peppa",
            listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb"), video("ccccccccccc"))
        )

        episodes.start(ask, setOf(MediaItemId("aaaaaaaaaaa"), MediaItemId("ccccccccccc")), DownloadChoice.Video(720))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), downloads.requested.map { it.first.value })
        assertEquals(listOf(720, 720), downloads.requestedHeights)
        assertNull(episodes.asking.value)
    }

    @Test
    fun `a torrent's files are fetched as files whatever was chosen`() = runTest {
        val downloads = FakeDownloadManager()
        val episodes = EpisodeDownloads(downloads, this)
        val ask = EpisodeDownloads.Ask("Peep Show", listOf(torrentFile("torrent:h:1"), torrentFile("torrent:h:2")))

        assertFalse(ask.offersAChoice)
        episodes.start(ask, ask.items.mapTo(mutableSetOf()) { it.item.id }, DownloadChoice.Sound)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(false, false), downloads.requested.map { it.second })
    }

    @Test
    fun `an album's songs come down as sound, and offer no picture choice`() = runTest {
        val downloads = FakeDownloadManager()
        val episodes = EpisodeDownloads(downloads, this)
        val song = video("aaaaaaaaaaa").let { it.copy(item = it.item.copy(contentKind = MediaContentKind.MUSIC)) }
        val ask = EpisodeDownloads.Ask("Pablo", listOf(song))

        assertFalse(ask.offersAChoice)
        episodes.start(ask, setOf(song.item.id), DownloadChoice.Video(null))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(true), downloads.requested.map { it.second })
    }

    @Test
    fun `asking offers every episode`() = runTest {
        val episodes = EpisodeDownloads(FakeDownloadManager(), this)

        episodes.ask("Peppa", listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb")))

        assertEquals(2, episodes.asking.value?.items?.size)
        assertTrue(episodes.asking.value!!.offersAChoice)
    }
}
