package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureTest {

    private fun item(url: String?) = MediaItem(
        id = MediaItemId("x"),
        sourceId = SourceId("src"),
        title = "a thing",
        publishedAt = null,
        duration = null,
        mediaUrl = url?.let(HttpUrl::of),
    )

    private val torrentStream = "https://home.test/ts/stream/Peep.Show.S01E02.mkv?link=abc&index=2&play&totumToken=t"
    private val remux = HttpUrl.of("https://home.test/ts/audio/abc/2/index.m3u8?totumToken=t")

    @Test
    fun `whether it has a picture does not depend on where it came from`() {
        val watch = "https://www.youtube.com/watch?v=aaaaaaaaaaa"
        val flac = "https://home.test/ts/stream/Album/01%20Song.flac?link=abc&index=0&play"
        val cases = listOf(
            PlayableItem(item(watch), PlayHandle.Video(HttpUrl.of(watch))) to Picture.YES,
            PlayableItem(item(torrentStream), PlayHandle.Podcast(audioUrl = remux)) to Picture.YES,
            PlayableItem(item(flac), PlayHandle.Podcast(audioUrl = remux)) to Picture.NO,
            PlayableItem(item("https://feeds.test/ep/123.mp4"), PlayHandle.Podcast()) to Picture.YES,
            PlayableItem(item("https://feeds.test/ep/123.MP3?utm=x"), PlayHandle.Podcast()) to Picture.NO,
            PlayableItem(item("https://feeds.test/ep/123"), PlayHandle.Podcast()) to Picture.UNKNOWN,
            PlayableItem(
                item("https://feeds.test/ep/123.mp4"),
                PlayHandle.Podcast(localPath = "/data/1.media"),
            ) to Picture.YES,
        )
        for ((playable, expected) in cases) assertEquals(playable.item.mediaUrl?.value, expected, playable.picture)
    }

    @Test
    fun `a torrent with a picture offers listen and watch, an audio file does not`() {
        assertTrue(PlayableItem(item(torrentStream), PlayHandle.Podcast(audioUrl = remux)).offersListenAndWatch)
        assertFalse(PlayableItem(item("https://feeds.test/ep/1.mp3"), PlayHandle.Podcast()).offersListenAndWatch)
    }
}
