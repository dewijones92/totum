package com.dewijones92.totum.music

import com.dewijones92.totum.innertube.actions.ActionResult
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.music.LibraryArtist
import com.dewijones92.totum.innertube.music.LibraryResult
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.YouTubeMusicLibrary
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFavouritesTest {

    private val album = MusicAlbumRef("MPREb_album", "An album", "An artist", "2020", MusicReleaseKind.ALBUM, null)
    private val library = FakeYouTubeMusicLibrary(
        albums = LibraryResult.Success(emptyList()),
        artists = LibraryResult.Success(emptyList()),
        likedSongCount = LibraryResult.Success(10),
    )
    private val actions = FakeYouTubeActions()
    private val follows = FakeFollows()
    private val favourites = MusicFavourites(library, actions, follows, pause = {})

    @Test
    fun `saving an album that the library then lists is done, and the shelf shows it`() = runBlocking {
        library.albums = LibraryResult.Success(listOf(album))

        val outcome = favourites.setAlbumSaved("MPREb_album", "OLAK5uy_a", "An album", saved = true)

        assertEquals(MusicFavourites.Outcome.DONE, outcome)
        assertEquals(listOf("OLAK5uy_a" to true), actions.savedPlaylistCalls)
        assertTrue(favourites.isSaved("MPREb_album"))
    }

    @Test
    fun `a save the library only lists on a later read is done, not unconfirmed`() = runBlocking {
        var reads = 0
        val lagging = object : YouTubeMusicLibrary by library {
            override suspend fun albums(): LibraryResult<List<MusicAlbumRef>> {
                reads++
                return LibraryResult.Success(if (reads >= 3) listOf(album) else emptyList())
            }
        }
        val favourites = MusicFavourites(lagging, actions, follows, pause = {})

        val outcome = favourites.setAlbumSaved("MPREb_album", "OLAK5uy_a", "An album", saved = true)

        assertEquals(MusicFavourites.Outcome.DONE, outcome)
        assertEquals(3, reads)
        assertTrue(favourites.isSaved("MPREb_album"))
    }

    @Test
    fun `an accepted save the library does not list is not confirmed`() = runBlocking {
        val outcome = favourites.setAlbumSaved("MPREb_album", "OLAK5uy_a", "An album", saved = true)

        assertEquals(MusicFavourites.Outcome.NOT_CONFIRMED, outcome)
        assertFalse(favourites.isSaved("MPREb_album"))
    }

    @Test
    fun `removing an album the library no longer lists is done`() = runBlocking {
        val outcome = favourites.setAlbumSaved("MPREb_album", "OLAK5uy_a", "An album", saved = false)

        assertEquals(MusicFavourites.Outcome.DONE, outcome)
        assertEquals(listOf("OLAK5uy_a" to false), actions.savedPlaylistCalls)
    }

    @Test
    fun `a refused save is a failure and never claims the album was saved`() = runBlocking {
        actions.result = ActionResult.Failure("STATUS_FAILED")
        library.albums = LibraryResult.Success(listOf(album))

        val outcome = favourites.setAlbumSaved("MPREb_album", "OLAK5uy_a", "An album", saved = true)

        assertEquals(MusicFavourites.Outcome.FAILED, outcome)
        assertFalse(favourites.isSaved("MPREb_album"))
    }

    @Test
    fun `an album with no playlist id asks YouTube nothing`() = runBlocking {
        val outcome = favourites.setAlbumSaved("MPREb_album", null, "An album", saved = true)

        assertEquals(MusicFavourites.Outcome.NOTHING_TO_SAVE, outcome)
        assertTrue(actions.savedPlaylistCalls.isEmpty())
    }

    @Test
    fun `liking a song reads the rating back and refreshes the liked count`() = runBlocking {
        library.likedSongCount = LibraryResult.Success(11)

        val outcome = favourites.setSongLiked("song1234567", "A song", liked = true)

        assertEquals(MusicFavourites.Outcome.DONE, outcome)
        assertEquals(listOf("song1234567" to VideoRating.LIKE), actions.ratingCalls)
        assertEquals(11, favourites.shelf.value.likedSongs)
        assertEquals(VideoRating.LIKE, favourites.songRating("song1234567"))
    }

    @Test
    fun `unliking clears the rating rather than disliking`() = runBlocking {
        actions.ratings = mapOf("song1234567" to VideoRating.LIKE)

        val outcome = favourites.setSongLiked("song1234567", "A song", liked = false)

        assertEquals(MusicFavourites.Outcome.DONE, outcome)
        assertEquals(listOf("song1234567" to VideoRating.NONE), actions.ratingCalls)
    }

    @Test
    fun `a like while signed out says so`() = runBlocking {
        actions.result = ActionResult.SignedOut

        assertEquals(MusicFavourites.Outcome.SIGNED_OUT, favourites.setSongLiked("song1234567", "A song", liked = true))
    }

    @Test
    fun `following an artist that then shows in the library is done`() = runBlocking {
        library.artists = LibraryResult.Success(listOf(LibraryArtist("Erik Satie", null, null)))

        val outcome = favourites.setArtistFollowed("UCartist", "erik satie", followed = true)

        assertEquals(MusicFavourites.Outcome.DONE, outcome)
        assertEquals(listOf(Triple("UCartist", "erik satie", true)), follows.calls)
        assertEquals(1, favourites.shelf.value.artists.size)
    }

    @Test
    fun `a follow the subscription write refused is a failure`() = runBlocking {
        follows.accept = false

        assertEquals(MusicFavourites.Outcome.FAILED, favourites.setArtistFollowed("UCartist", "Erik", followed = true))
    }

    @Test
    fun `whether an artist is followed is unknown until the subscriptions are known`() = runBlocking {
        assertNull(favourites.followed("UCartist").first())

        follows.ids.value = setOf("UCartist")
        assertEquals(true, favourites.followed("UCartist").first())
        assertEquals(false, favourites.followed("UCother").first())
    }

    @Test
    fun `refresh reads the liked count, saved albums and followed artists`() = runBlocking {
        library.albums = LibraryResult.Success(listOf(album))
        library.artists = LibraryResult.Success(listOf(LibraryArtist("Erik Satie", null, null)))

        favourites.refresh()

        val shelf = favourites.shelf.value
        assertFalse(shelf.loading)
        assertEquals(10, shelf.likedSongs)
        assertEquals(1, shelf.albums.size)
        assertEquals(1, shelf.artists.size)
    }

    @Test
    fun `refresh while signed out shows signed out with nothing on the shelf`() = runBlocking {
        val signedOut = MusicFavourites(FakeYouTubeMusicLibrary(), actions, follows)

        signedOut.refresh()

        assertTrue(signedOut.shelf.value.signedOut)
        assertTrue(signedOut.shelf.value.isEmpty)
    }

    @Test
    fun `a liked list whose count YouTube did not say still counts as something to show`() = runBlocking {
        library.likedSongCount = LibraryResult.Success(null)

        favourites.refresh()

        assertFalse(favourites.shelf.value.isEmpty)
        assertNull(favourites.shelf.value.likedSongs)
    }

    @Test
    fun `a first read cancelled part way is asked again by the next page`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val slow = object : YouTubeMusicLibrary by library {
            override suspend fun albums(): LibraryResult<List<MusicAlbumRef>> {
                reads++
                if (reads == 1) gate.await()
                return LibraryResult.Success(listOf(album))
            }
        }
        val favourites = MusicFavourites(slow, actions, follows, pause = {})

        val first = launch { favourites.loadOnce() }
        repeat(MAX_YIELDS) { if (reads == 0) yield() }
        assertEquals("the first read must be waiting inside the library when it is cancelled", 1, reads)
        first.cancelAndJoin()
        gate.complete(Unit)
        assertFalse("a cancelled read must not leave the shelf loading for ever", favourites.shelf.value.loading)

        favourites.loadOnce()
        assertEquals(2, reads)
        assertTrue(favourites.isSaved("MPREb_album"))
    }

    private companion object {
        const val MAX_YIELDS = 20
    }

    private class FakeFollows : MusicFavourites.Follows {
        val ids = MutableStateFlow<Set<String>?>(null)
        var accept = true
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        override val followedChannelIds = ids

        override suspend fun set(channelId: String, name: String, followed: Boolean): Boolean {
            calls += Triple(channelId, name, followed)
            return accept
        }
    }
}
