package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.FollowedSources
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BookChaptersHaveNoFollowMarkTest {

    private val rows = RowFollowing(FollowedSources(emptyList(), emptyList(), signedIn = true), null)

    private fun item(kind: MediaContentKind) = MediaItem(
        id = MediaItemId("https://archive.org/download/x/1.mp3"),
        sourceId = SourceId("podcast:x"),
        title = "Chapter 1",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://archive.org/download/x/1.mp3"),
        contentKind = kind,
    )

    @Test
    fun `a book chapter has no follow mark, an episode still does`() {
        assertNull(rows.markFor(item(MediaContentKind.AUDIOBOOK), MediaKind.PODCAST))
        assertNotNull(rows.markFor(item(MediaContentKind.STANDARD), MediaKind.PODCAST))
    }

    @Test
    fun `a file from a torrent has no follow mark either, it came from no channel or show`() {
        val film = item(
            MediaContentKind.STANDARD
        ).copy(sourceId = com.dewijones92.totum.data.torrent.TorrentPlayables.SOURCE)
        assertNull(rows.markFor(film, MediaKind.PODCAST))
    }
}
