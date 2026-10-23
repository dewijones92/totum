package com.dewijones92.totum.ui.music

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.actions.ActionResult
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.actions.fake.FakeYouTubeActions
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.music.LibraryArtist
import com.dewijones92.totum.innertube.music.LibraryResult
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.YouTubeMusicLibrary
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.innertube.subscriptions.SubscribedChannel
import com.dewijones92.totum.innertube.subscriptions.SubscriptionsResult
import com.dewijones92.totum.innertube.subscriptions.fake.FakeYouTubeSubscriptions
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.video.AccountSubscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MusicFavouritesScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val actions = FakeYouTubeActions()
    private val library = AccountLibrary(actions)
    private val account = YouTubeAccount(
        FakeYouTubeAuth(),
        InMemoryTokenStore(OAuthTokens(AccessToken("at"), RefreshToken("rt"), FAR_FUTURE)),
    )
    private val subscriptions = FakeYouTubeSubscriptions()

    private val album = MusicAlbum(
        browseId = "MPREb_album",
        title = "Gymnopedies",
        artist = "Erik Satie",
        artistBrowseId = "UCsatie",
        year = "1888",
        kind = MusicReleaseKind.ALBUM,
        summary = null,
        description = null,
        thumbnailUrl = null,
        playlistId = "OLAK5uy_album",
        tracks = emptyList(),
    )
    private val artist = MusicArtist(
        browseId = "UCsatie",
        name = "Erik Satie",
        description = null,
        thumbnailUrl = null,
        topSongs = emptyList(),
        allSongs = null,
        allAlbums = null,
        allSingles = null,
        albums = emptyList(),
        singles = emptyList(),
        similar = emptyList(),
        radio = null,
        shuffle = null,
    )

    private fun container(): FakeAppContainer {
        val scope = CoroutineScope(SupervisorJob())
        return FakeAppContainer(
            youTubeAccount = account,
            accountSubscriptions = AccountSubscriptions(subscriptions, actions, account, scope),
            youTubeActions = actions,
            musicCatalogue = FakeYouTubeMusicCatalogue(
                albumsById = mapOf(album.browseId to album),
                artistsById = mapOf(artist.browseId to artist),
            ),
            musicLibrary = library,
        )
    }

    private fun text(res: Int) = context.getString(res)

    @Test
    fun savingAnAlbumWritesItsPlaylistAndShowsSavedOnceTheLibraryHasIt() {
        rule.setContent {
            TotumTheme { MusicAlbumScreen(container(), MusicPage.Album(album.browseId, title = album.title)) {} }
        }

        rule.waitUntil(
            TIMEOUT_MS
        ) { rule.onAllNodes(hasText(text(R.string.music_save))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SAVE_ALBUM_TAG).performClick()

        rule.waitUntil(
            TIMEOUT_MS
        ) { rule.onAllNodes(hasText(text(R.string.music_saved))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("OLAK5uy_album" to true), actions.savedPlaylistCalls)
    }

    @Test
    fun removingASavedAlbumAsksFirstAndKeepingItChangesNothing() {
        library.saved += album.browseId
        rule.setContent {
            TotumTheme { MusicAlbumScreen(container(), MusicPage.Album(album.browseId, title = album.title)) {} }
        }
        rule.waitUntil(
            TIMEOUT_MS
        ) { rule.onAllNodes(hasText(text(R.string.music_saved))).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(SAVE_ALBUM_TAG).performClick()
        rule.onNodeWithText(text(R.string.music_keep)).performClick()
        assertEquals(emptyList<Pair<String, Boolean>>(), actions.savedPlaylistCalls)

        rule.onNodeWithTag(SAVE_ALBUM_TAG).performClick()
        rule.onNodeWithText(text(R.string.music_unsave)).performClick()
        rule.waitUntil(
            TIMEOUT_MS
        ) { rule.onAllNodes(hasText(text(R.string.music_save))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("OLAK5uy_album" to false), actions.savedPlaylistCalls)
    }

    @Test
    fun aRefusedSaveNeverShowsSaved() {
        actions.result = ActionResult.Failure("STATUS_FAILED")
        rule.setContent {
            TotumTheme { MusicAlbumScreen(container(), MusicPage.Album(album.browseId, title = album.title)) {} }
        }
        rule.waitUntil(
            TIMEOUT_MS
        ) { rule.onAllNodes(hasText(text(R.string.music_save))).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(SAVE_ALBUM_TAG).performClick()
        rule.waitUntil(TIMEOUT_MS) { actions.savedPlaylistCalls.isNotEmpty() }
        rule.waitForIdle()

        rule.onNodeWithText(text(R.string.music_save)).assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText(text(R.string.music_saved))).fetchSemanticsNodes().size)
    }

    @Test
    fun followingAnArtistSubscribesToItsChannel() {
        val built = container()
        built.accountSubscriptions.refresh(force = true)
        rule.setContent { TotumTheme { MusicArtistScreen(built, MusicPage.Artist(artist.browseId, artist.name)) {} } }

        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText(text(R.string.music_follow))).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag(FOLLOW_ARTIST_TAG).performClick()

        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText(text(R.string.music_following))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("UCsatie" to true), actions.subscribeCalls)
    }

    @Test
    fun anArtistAlreadyFollowedSaysFollowing() {
        subscriptions.result = SubscriptionsResult.Success(
            listOf(SubscribedChannel("UCsatie", "Erik Satie", HttpUrl.of(SATIE_CHANNEL), null)),
        )
        val built = container()
        built.accountSubscriptions.refresh(force = true)
        rule.setContent { TotumTheme { MusicArtistScreen(built, MusicPage.Artist(artist.browseId, artist.name)) {} } }

        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasText(text(R.string.music_following))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theHomeShowsLikedSongsSavedAlbumsAndFollowedArtistsUnderFavourites() {
        library.saved += album.browseId
        library.artists = listOf(LibraryArtist("Erik Satie", null, null))
        rule.setContent { TotumTheme { MusicScreen(container()) } }

        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(hasTestTag(FAVOURITES_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(LIKED_SONGS_TAG).assertIsDisplayed()
        rule.onNodeWithText("1,206", substring = true).assertIsDisplayed()
        rule.onNodeWithText(album.title).assertIsDisplayed()
        rule.onNodeWithText("Erik Satie").assertIsDisplayed()
    }

    @Test
    fun aSongsMenuStarLikesItOnTheAccount() {
        val song = MediaItem(
            MediaItemId("Nn_CcTBataE"),
            SourceId("music:album:x"),
            "Sevillana",
            publishedAt = null,
            duration = null,
            contentKind = MediaContentKind.MUSIC,
        )
        val built = container()
        rule.setContent {
            TotumTheme {
                ProvidePlayStates(built, onOpenSource = {}) {
                    MediaItemRow(item = song, subtitleLines = emptyList(), pillar = MediaKind.VIDEO, onPlay = {})
                }
            }
        }

        rule.onNodeWithContentDescription(text(R.string.queue_menu)).performClick()
        rule.waitUntil(
            TIMEOUT_MS
        ) { rule.onAllNodes(hasText(text(R.string.star))).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(text(R.string.star)).performClick()

        rule.waitUntil(TIMEOUT_MS) { actions.ratingCalls.isNotEmpty() }
        assertEquals(listOf("Nn_CcTBataE" to VideoRating.LIKE), actions.ratingCalls)
    }

    private class AccountLibrary(private val actions: FakeYouTubeActions) : YouTubeMusicLibrary {
        val saved = mutableSetOf<String>()
        var artists: List<LibraryArtist> = emptyList()

        override suspend fun albums(): LibraryResult<List<MusicAlbumRef>> {
            actions.savedPlaylistCalls.lastOrNull()?.let { (_, on) ->
                if (actions.result is ActionResult.Success) {
                    if (on) saved += "MPREb_album" else saved -= "MPREb_album"
                }
            }
            return LibraryResult.Success(
                saved.map { MusicAlbumRef(it, "Gymnopedies", "Erik Satie", null, MusicReleaseKind.ALBUM, null) },
            )
        }

        override suspend fun recentAlbums(): LibraryResult<List<MusicAlbumRef>> = LibraryResult.Success(emptyList())

        override suspend fun playlists(): LibraryResult<List<Playlist>> = LibraryResult.Success(emptyList())

        override suspend fun artists(): LibraryResult<List<LibraryArtist>> =
            LibraryResult.Success(
                artists + actions.subscribeCalls.filter {
                    it.second
                }.map { LibraryArtist("Erik Satie", null, null) }
            )

        override suspend fun likedSongCount(): LibraryResult<Int?> = LibraryResult.Success(1206)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val FAR_FUTURE = 4_000_000_000L
        const val SATIE_CHANNEL = "https://www.youtube.com/channel/UCsatie"
    }
}
