package com.dewijones92.totum.playback

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class TheNotificationSaysWhatIsNextTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var refreshes = 0
    private var playing: String? = "a"

    private val provider = ContextualNotificationProvider(context, { refreshes++ }, { playing })

    private fun tell(shown: PlaybackContext) = provider.onCommand(shown.toBundle())

    private fun text(artist: String? = "Artist"): String? {
        val method = ContextualNotificationProvider::class.java
            .getDeclaredMethod("getNotificationContentText", MediaMetadata::class.java)
            .apply { isAccessible = true }
        val metadata = MediaMetadata.Builder().apply { artist?.let(::setArtist) }.build()
        return method.invoke(provider, metadata)?.toString()
    }

    @Test
    fun whatIsQueuedNextLeadsTheText_aheadOfTheArtist() {
        tell(PlaybackContext(nextTitle = "Title b", nextAfter = "a"))

        assertEquals("⏭️ Next: Title b · Artist", text())
    }

    @Test
    fun withNoArtistTheNextLineStandsAlone() {
        tell(PlaybackContext(nextTitle = "Title b", nextAfter = "a"))

        assertEquals("⏭️ Next: Title b", text(artist = null))
    }

    @Test
    fun aNextLineThatFollowsAnotherItemIsHeldBackRatherThanShownForTheWrongOne() {
        tell(PlaybackContext(nextTitle = "Title b", nextAfter = "a"))
        playing = "z"

        assertEquals("Artist", text())
    }

    @Test
    fun repeatOneSaysSoAndNamesNothingNext() {
        tell(PlaybackContext(repeatOne = true, nextTitle = "Title b", nextAfter = "a"))

        assertEquals("🔂 Repeating this one · Artist", text())
    }

    @Test
    fun exploreSaysItPicksWhatFollows() {
        tell(PlaybackContext(explorePicksNext = true, nextAfter = "a"))

        assertEquals("🧭 Explore picks what follows · Artist", text())
    }

    @Test
    fun aLiveBadgeComesFirstThenTheNextLine() {
        tell(PlaybackContext(nextTitle = "Title b", nextAfter = "a", live = PlayingLive.LIVE))

        assertEquals("🔴 LIVE · ⏭️ Next: Title b · Artist", text())
        tell(PlaybackContext(live = PlayingLive.WAS_LIVE))
        assertEquals("⏺️ WAS LIVE · Artist", text())
    }

    @Test
    fun nothingToSayLeavesTheArtistAlone() {
        tell(PlaybackContext())

        assertEquals("Artist", text())
        assertNull(text(artist = null))
    }

    @Test
    fun aChangeRefreshesTheNotificationOnceAndTheSameContextAgainDoesNot() {
        tell(PlaybackContext(nextTitle = "Title b", nextAfter = "a"))
        tell(PlaybackContext(nextTitle = "Title b", nextAfter = "a"))
        assertEquals(1, refreshes)

        tell(PlaybackContext(nextTitle = "Title c", nextAfter = "a"))
        assertEquals(2, refreshes)
    }

    @Test
    fun theContextCrossesTheSessionCommandIntact() {
        val original = PlaybackContext(
            repeatOne = true,
            nextTitle = "Title b",
            explorePicksNext = true,
            nextAfter = "a",
            live = PlayingLive.WAS_LIVE,
        )

        assertEquals(original, PlaybackContext.from(original.toBundle()))
        assertEquals(PlaybackContext(), PlaybackContext.from(PlaybackContext().toBundle()))
        assertTrue(provider.handles(ACTION_PLAYBACK_CONTEXT))
    }
}
