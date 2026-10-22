package com.dewijones92.totum.pins

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinMenuTest {

    private val trainVideo = PlayableItem(
        MediaItem(MediaItemId("trainVideo1"), SourceId("yt"), "Trains", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=trainVideo1")),
    )
    private val album = Pin.Album("MPREb_album", "Pablo", null)
    private val artist = Pin.Artist("UCartist", "Miss Rachel", null, radioPlaylistId = "RDartist")
    private val radio = Pin.Radio(videoId = "seedseedsee", playlistId = "RDseed", title = "Radio", artUrl = null)

    @Test
    fun `an album tile offers the whole album set and the common actions`() {
        assertEquals(
            listOf(
                PinAction.PLAY, PinAction.SHUFFLE, PinAction.PLAY_NEXT, PinAction.ADD_TO_QUEUE,
                PinAction.START_RADIO, PinAction.GO_TO_ALBUM, PinAction.GO_TO_ARTIST, PinAction.SAVE_ALBUM,
                PinAction.TOGGLE_PIN, PinAction.ADD_TO_HOME,
            ),
            pinMenuFor(album, inRecent = false),
        )
    }

    @Test
    fun `only a Recently tile can be removed from Recently`() {
        assertTrue(PinAction.REMOVE_FROM_RECENT in pinMenuFor(album, inRecent = true))
        assertFalse(PinAction.REMOVE_FROM_RECENT in pinMenuFor(album, inRecent = false))
    }

    @Test
    fun `an artist known only by name cannot be shuffled or followed, but radio and go-to stay`() {
        val byName = artist.copy(browseId = null)
        assertEquals(
            listOf(PinAction.ARTIST_RADIO, PinAction.GO_TO_ARTIST, PinAction.TOGGLE_PIN, PinAction.ADD_TO_HOME),
            pinMenuFor(byName, inRecent = false),
        )
    }

    @Test
    fun `a radio restarts and goes to its seed only when it has one`() {
        assertTrue(PinAction.GO_TO_SEED in pinMenuFor(radio, inRecent = false))
        assertFalse(PinAction.GO_TO_SEED in pinMenuFor(radio.copy(videoId = null), inRecent = false))
    }

    @Test
    fun `a song tile has no tile menu of its own, so the song menu opens instead`() {
        val video = pinMenuFor(Pin.of(trainVideo), inRecent = true)
        assertEquals(
            listOf(
                PinAction.TOGGLE_PIN,
                PinAction.ADD_TO_HOME,
                PinAction.ADD_TO_HOME_LOCKED,
                PinAction.REMOVE_FROM_RECENT
            ),
            video,
        )
        val song = trainVideo.copy(item = trainVideo.item.copy(contentKind = MediaContentKind.MUSIC))
        assertEquals(
            "a song is never locked for a child",
            listOf(PinAction.TOGGLE_PIN, PinAction.ADD_TO_HOME, PinAction.REMOVE_FROM_RECENT),
            pinMenuFor(Pin.of(song), inRecent = true),
        )
    }

    @Test
    fun `an artist with no id and no radio cannot be pinned, because the pin could never play`() {
        assertEquals(
            listOf(PinAction.GO_TO_ARTIST),
            pinMenuFor(artist.copy(browseId = null, radioPlaylistId = null), inRecent = false),
        )
    }
}
