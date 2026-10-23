package com.dewijones92.totum.stars

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.playlist.fake.InMemoryLocalPlaylistStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.music.MusicFavourites.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarsTest {

    private val episode = PlayableItem(
        MediaItem(
            id = MediaItemId("torrent:abc:6"),
            sourceId = SourceId("podcast:peep show"),
            title = "S01E06",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://home.example/ts/stream/s01e06.mkv"),
        ),
        PlayHandle.Podcast(),
    )

    private val video = PlayableItem(
        MediaItem(
            id = MediaItemId("dQw4w9WgXcQ"),
            sourceId = SourceId("channel:x"),
            title = "Never Gonna Give You Up",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")),
    )

    private val likes = mutableListOf<Pair<String, Boolean>>()
    private var likeAnswer = Outcome.DONE

    private fun stars(store: InMemoryLocalPlaylistStore = InMemoryLocalPlaylistStore()) =
        Stars(store) { id, _, liked ->
            likes += id to liked
            likeAnswer
        }

    @Test
    fun `starring a podcast episode keeps it here and asks nothing of YouTube`() = runTest {
        val stars = stars()

        val outcome = stars.setStarred(episode, true)

        assertEquals(StarOutcome(starredHere = true, account = null), outcome)
        assertTrue(episode.item.id in stars.starredIds.first())
        assertEquals(emptyList<Pair<String, Boolean>>(), likes)
    }

    @Test
    fun `starring a YouTube video likes it on the account, and unstarring removes the like`() = runTest {
        val stars = stars()

        stars.setStarred(video, true)
        val off = stars.setStarred(video, false)

        assertEquals(listOf("dQw4w9WgXcQ" to true, "dQw4w9WgXcQ" to false), likes)
        assertEquals(StarOutcome(starredHere = false, account = Outcome.DONE), off)
        assertFalse(video.item.id in stars.starredIds.first())
    }

    @Test
    fun `a like YouTube refuses still leaves the star here, and says so`() = runTest {
        likeAnswer = Outcome.SIGNED_OUT
        val stars = stars()

        val outcome = stars.setStarred(video, true)

        assertEquals(StarOutcome(starredHere = true, account = Outcome.SIGNED_OUT), outcome)
        assertTrue(video.item.id in stars.starredIds.first())
    }

    @Test
    fun `the stars live in one playlist the backup carries, under a fixed id`() = runTest {
        val store = InMemoryLocalPlaylistStore()
        stars(store).setStarred(episode, true)

        val playlist = store.observePlaylists().first().single()

        assertEquals(STARRED_PLAYLIST, playlist.id)
        assertEquals(1, playlist.itemCount)
    }
}
