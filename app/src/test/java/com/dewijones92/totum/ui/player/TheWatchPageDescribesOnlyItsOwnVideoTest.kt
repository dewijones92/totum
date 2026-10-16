package com.dewijones92.totum.ui.player

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TheWatchPageDescribesOnlyItsOwnVideoTest {

    private fun playing(id: String, kind: MediaKind) = PlaybackState(
        itemId = MediaItemId(id),
        title = id,
        artist = null,
        artworkUrl = null,
        isPlaying = true,
        positionMs = 0,
        durationMs = 1_000,
        speed = 1f,
        chapters = emptyList(),
        kind = kind,
    )

    private val watchPage = WatchViewModel.RelatedState.Loaded(emptyList(), "The video's description", videoId = "v1")

    @Test
    fun `the video the watch page was fetched for gets its description`() {
        assertEquals("The video's description", detailsDescription(playing("v1", MediaKind.VIDEO), watchPage))
    }

    @Test
    fun `a podcast playing after that video does not get the video's description`() {
        assertNull(detailsDescription(playing("episode", MediaKind.PODCAST), watchPage))
    }

    @Test
    fun `another video does not get it either`() {
        assertNull(detailsDescription(playing("v2", MediaKind.VIDEO), watchPage))
    }
}
