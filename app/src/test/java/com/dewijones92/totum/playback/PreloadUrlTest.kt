package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreloadUrlTest {

    private val stream = HttpUrl.of("https://home.example/ts/stream/Film.mkv?link=abc&index=1&play")
    private val playlist = HttpUrl.of("https://home.example/ts/audio/abc/1/index.m3u8")

    private fun torrent(audioUrl: HttpUrl? = playlist, localPath: String? = null) = PlayableItem(
        MediaItem(
            id = MediaItemId("torrent:abc:1"),
            sourceId = SourceId("torrent"),
            title = "Film",
            publishedAt = null,
            duration = null,
            mediaUrl = stream,
        ),
        PlayHandle.Podcast(audioUrl = audioUrl, localPath = localPath),
    )

    @Test
    fun `a torrent's audio playlist is never held, its stream is`() {
        assertEquals(stream, torrent().preloadableUrl())
    }

    @Test
    fun `an ordinary podcast's audio is held, a file on the phone is not`() {
        val mp3 = HttpUrl.of("https://cdn.example/ep.mp3")
        assertEquals(mp3, torrent(audioUrl = mp3).preloadableUrl())
        assertNull(torrent(localPath = "/data/x.media").preloadableUrl())
    }
}
