package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.JellyfinApi
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.library.JellyfinScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class JellyfinScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val showId = "0123456789abcdef0123456789abcdef"
    private val episodeId = "fedcba9876543210fedcba9876543210"
    private val filmId = "00112233445566778899aabbccddeeff"

    private val show = JellyfinItem(showId, "Bluey", JellyfinKind.SERIES)
    private val film = JellyfinItem(filmId, "Heat", JellyfinKind.MOVIE, year = 1995, container = "mkv")
    private val episode = JellyfinItem(
        episodeId,
        "Mr Wolf",
        JellyfinKind.EPISODE,
        seriesName = "Bluey",
        seasonNumber = 1,
        episodeNumber = 4,
        container = "mkv",
    )

    private class FakeJellyfin(val answers: MutableMap<Pair<String?, Int>, HomeAnswer<JellyfinListing>>) : JellyfinApi {
        val asked = mutableListOf<Pair<String?, Int>>()
        override suspend fun browse(parent: String?, start: Int): HomeAnswer<JellyfinListing> {
            asked += parent to start
            return answers[parent to start] ?: HomeAnswer.No("❌ nothing for $parent/$start")
        }

        override fun streamUrl(item: JellyfinItem): HttpUrl = HttpUrl.of(
            "https://pi.example/jellyfin/stream/${item.id}.mkv"
        )

        override fun imageUrl(item: JellyfinItem): HttpUrl = HttpUrl.of("https://pi.example/jellyfin/image/${item.id}")
    }

    private var backed = false

    private fun show(jellyfin: FakeJellyfin): FakeAppContainer {
        val container = FakeAppContainer(jellyfin = jellyfin)
        compose.setContent { TotumTheme { JellyfinScreen(container, onBack = { backed = true }) } }
        compose.waitForIdle()
        return container
    }

    private fun top(vararg items: JellyfinItem, next: Int? = null) =
        HomeAnswer.Ok(JellyfinListing(null, "Jellyfin", items.toList(), next = next))

    @Test
    fun theTopLevelListsFolderAndFilm() {
        show(FakeJellyfin(mutableMapOf((null to 0) to top(show, film))))

        compose.onNodeWithText("📺 Bluey").assertIsDisplayed()
        compose.onNodeWithText("Heat").assertIsDisplayed()
    }

    @Test
    fun aFailureShowsItsReasonAndTryAgainReadsAgain() {
        val pi = FakeJellyfin(mutableMapOf((null to 0) to HomeAnswer.No("ℹ️ Jellyfin isn't set up")))
        show(pi)
        compose.onNodeWithText("ℹ️ Jellyfin isn't set up").assertIsDisplayed()

        pi.answers[null to 0] = top(film)
        compose.onNodeWithText(text(R.string.jellyfin_retry)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertIsDisplayed()
        assertEquals(2, pi.asked.size)
    }

    @Test
    fun aFolderOpensItsChildrenAndBackReturnsToTheParent() {
        val pi = FakeJellyfin(
            mutableMapOf(
                (null to 0) to top(show, film),
                (showId to 0) to HomeAnswer.Ok(JellyfinListing(showId, "Bluey", listOf(episode))),
            ),
        )
        show(pi)

        compose.onNodeWithText("📺 Bluey").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("S01E04 · Mr Wolf").assertIsDisplayed()

        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertIsDisplayed()
        assertTrue("not left the screen: $backed", !backed)
        assertEquals("the top level is not read again on the way back", listOf(null to 0, showId to 0), pi.asked)
    }

    @Test
    fun backOnTheTopLevelLeavesTheScreen() {
        show(FakeJellyfin(mutableMapOf((null to 0) to top(film))))

        compose.onNodeWithContentDescription(text(R.string.back)).performClick()

        assertTrue(backed)
    }

    @Test
    fun anEmptyFolderSaysSo() {
        show(FakeJellyfin(mutableMapOf((null to 0) to top())))

        compose.onNodeWithText(text(R.string.jellyfin_empty)).assertIsDisplayed()
    }

    @Test
    fun showMoreAsksForTheNextPageAndAddsItBelow() {
        val pi = FakeJellyfin(
            mutableMapOf(
                (null to 0) to top(film, next = 1),
                (null to 1) to HomeAnswer.Ok(JellyfinListing(null, "Jellyfin", listOf(show))),
            ),
        )
        show(pi)

        compose.onNodeWithText(text(R.string.jellyfin_more)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("📺 Bluey").assertIsDisplayed()
        compose.onNodeWithText("Heat").assertIsDisplayed()
        assertEquals(listOf(null to 0, null to 1), pi.asked)
        assertEquals(0, compose.onAllNodesWithText(text(R.string.jellyfin_more)).fetchSemanticsNodes().size)
    }

    @Test
    fun tappingAFilmPlaysItThroughTheQueueFromTheTopLevelGroup() {
        val container = show(FakeJellyfin(mutableMapOf((null to 0) to top(film, show))))

        compose.onNodeWithText("Heat").performClick()
        compose.waitForIdle()

        val queued = container.playbackQueue.state.value.entries
        assertEquals(listOf("jellyfin:$filmId"), queued.map { it.item.item.id.value })
        assertEquals("Jellyfin", queued.single().group?.title)
        assertEquals("1995", queued.single().item.item.publishedText)
        assertEquals("https://pi.example/jellyfin/image/$filmId", queued.single().item.item.thumbnailUrl?.value)
    }
}
