package com.dewijones92.totum.ui.music

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.RadioSeed
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.ProvidePlayStates
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoverButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val song = MediaItem(
        id = MediaItemId("seedSong001"),
        sourceId = SourceId("music:radio:x"),
        title = "A song",
        publishedAt = null,
        duration = null,
        author = "Erik Satie",
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=seedSong001"),
        contentKind = MediaContentKind.MUSIC,
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
        radio = RadioSeed("seedSong001", "RDEMsatie"),
        shuffle = null,
    )
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

    private fun container() = FakeAppContainer(
        musicCatalogue = FakeYouTubeMusicCatalogue(
            albumsById = mapOf(album.browseId to album),
            artistsById = mapOf(artist.browseId to artist),
        ),
    )

    private fun waitFor(text: String) = rule.waitUntil(TIMEOUT_MS) {
        rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertDiscoverFollowsRadio(radio: String, discover: String) {
        val radioBounds = rule.onNodeWithText(radio).getUnclippedBoundsInRoot()
        val discoverBounds = rule.onNodeWithText(discover).getUnclippedBoundsInRoot()
        val after = discoverBounds.top > radioBounds.top ||
            (discoverBounds.top == radioBounds.top && discoverBounds.left > radioBounds.left)
        assertTrue("$discover should come after $radio: $discoverBounds vs $radioBounds", after)
    }

    @Test
    fun theSongMenuOffersDiscoverRightAfterStartRadio() {
        val container = container()
        rule.setContent {
            TotumTheme {
                ProvidePlayStates(container, onOpenSource = {}) {
                    MediaItemRow(item = song, subtitleLines = emptyList(), pillar = MediaKind.VIDEO, onPlay = {})
                }
            }
        }
        rule.onNodeWithContentDescription(context.getString(R.string.queue_menu)).performClick()

        val radio = context.getString(R.string.music_start_radio)
        val discover = context.getString(R.string.music_start_discover)
        rule.onNodeWithText(radio).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(discover).performScrollTo().assertIsDisplayed()
        assertDiscoverFollowsRadio(radio, discover)
    }

    @Test
    fun theArtistPageOffersDiscoverBesideMix() {
        val container = container()
        rule.setContent {
            TotumTheme { MusicArtistScreen(container, MusicPage.Artist(artist.browseId, artist.name)) {} }
        }
        waitFor(context.getString(R.string.music_mix))

        rule.onNodeWithText(context.getString(R.string.music_mix)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.music_discover)).assertIsDisplayed()
        assertDiscoverFollowsRadio(context.getString(R.string.music_mix), context.getString(R.string.music_discover))
    }

    @Test
    fun theAlbumPageOffersDiscoverBesideRadio() {
        val container = container()
        rule.setContent {
            TotumTheme { MusicAlbumScreen(container, MusicPage.Album(album.browseId, title = album.title)) {} }
        }
        waitFor(context.getString(R.string.music_radio))

        rule.onNodeWithText(context.getString(R.string.music_radio)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.music_discover)).assertIsDisplayed()
        assertDiscoverFollowsRadio(context.getString(R.string.music_radio), context.getString(R.string.music_discover))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
