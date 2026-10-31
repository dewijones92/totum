package com.dewijones92.totum.ui.common

import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.DownloadChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadLabelsTest {

    private fun item(url: String, kind: MediaContentKind = MediaContentKind.STANDARD) = MediaItem(
        id = MediaItemId(url),
        sourceId = SourceId("s"),
        title = "t",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of(url),
        contentKind = kind,
    )

    @Test
    fun `every download says what it will fetch, from the remembered choice`() {
        val video = item("https://www.youtube.com/watch?v=abc")
        assertEquals(R.string.download_the_sound, downloadLabel(video, DownloadChoice.Sound))
        assertEquals(R.string.download_the_video, downloadLabel(video, DownloadChoice.Video(720)))
        assertEquals(R.string.download_choose, downloadLabel(video, null))
        assertEquals(R.string.download_the_file, downloadLabel(item("https://feed.test/ep1.mp3"), DownloadChoice.Sound))
        assertEquals(
            R.string.download_the_song,
            downloadLabel(item("https://www.youtube.com/watch?v=s", MediaContentKind.MUSIC), DownloadChoice.Sound),
        )
    }

    @Test
    fun `a torrent's film or a video podcast is fetched whole, so it never says sound only`() {
        val torrentFilm = item("https://home.test/ts/stream/Night.of.the.Living.Dead.1968.mkv?link=h&index=1&play")
        val videoEpisode = item("https://feed.test/episode-12.mp4")

        assertEquals(R.string.download_the_file, downloadLabel(torrentFilm, DownloadChoice.Sound))
        assertEquals(R.string.download_the_file, downloadLabel(videoEpisode, DownloadChoice.Sound))
    }
}
