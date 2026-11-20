package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.data.phone.ImportedPhoneMedia
import com.dewijones92.totum.data.phone.PhoneMediaPermissions
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.phonemedia.MediaStorePhoneSource
import com.dewijones92.totum.phonemedia.PhoneMediaPermissionState
import com.dewijones92.totum.phonemedia.PhoneMediaServices
import com.dewijones92.totum.playback.ScreenTarget
import com.dewijones92.totum.playback.ScreenTargets
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.support.PhoneMediaFixtures
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.library.GOOGLE_CARD_TAG
import com.dewijones92.totum.ui.library.PHONE_ALLOW_TAG
import com.dewijones92.totum.ui.library.PHONE_SEARCH_TAG
import com.dewijones92.totum.ui.library.PHONE_SETTINGS_TAG
import com.dewijones92.totum.ui.library.PHOTO_CLOSE_TAG
import com.dewijones92.totum.ui.library.PHOTO_GRID_TAG
import com.dewijones92.totum.ui.library.PHOTO_SCREEN_BUTTON_TAG
import com.dewijones92.totum.ui.library.PHOTO_SCREEN_ITEM_TAG
import com.dewijones92.totum.ui.library.PHOTO_SCREEN_NONE_TAG
import com.dewijones92.totum.ui.library.PHOTO_SCREEN_STOP_TAG
import com.dewijones92.totum.ui.library.PHOTO_SLIDESHOW_TAG
import com.dewijones92.totum.ui.library.PHOTO_VIEWER_TAG
import com.dewijones92.totum.ui.library.PhoneMediaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class PhoneMediaScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int): String = context.getString(id)

    @Before
    fun fixtures() {
        PhoneMediaFixtures.grantMediaPermissions()
        PhoneMediaFixtures.video(context, "totum-phone-video.mp4")
        PhoneMediaFixtures.photo(context, "totum-phone-red.jpg", android.graphics.Color.RED)
        PhoneMediaFixtures.photo(context, "totum-phone-blue.jpg", android.graphics.Color.BLUE)
    }

    private var backed = false

    private fun show(
        permissions: PhoneMediaPermissionState = PhoneMediaPermissionState.everythingGranted(),
    ): FakeAppContainer {
        val container = FakeAppContainer(
            phoneMedia = PhoneMediaServices.inMemory(
                device = MediaStorePhoneSource(context),
                imports = ImportedPhoneMedia(Files.createTempDirectory("phone-test").toFile()),
                permissions = permissions,
            ),
        )
        compose.setContent { TotumTheme { PhoneMediaScreen(container, onBack = { backed = true }) } }
        compose.waitForIdle()
        return container
    }

    private fun access(vararg granted: String, asked: Boolean) =
        PhoneMediaPermissionState(isGranted = { it in granted }, asked = { asked }, setAsked = {})

    @Test
    fun theVideosTabListsWhatIsOnThePhoneWithItsFolderAndNoDownloadControl() {
        show()

        compose.waitUntil(15_000) { compose.onAllNodesWithText("totum-phone-video").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("totum-phone-video").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phone_tab_videos)).assertIsDisplayed()
    }

    @Test
    fun tappingAVideoOnThePhoneQueuesItAndStartsItThoughItHasNoStreamAddress() {
        val container = show()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("totum-phone-video").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithText("totum-phone-video").onFirst().performClick()
        compose.waitForIdle()

        val current = container.playbackQueue.state.value.current?.item?.item
        assertTrue("the queue's current item was $current", current?.id?.value?.startsWith("phone:video:") == true)
        assertTrue(current?.mediaUrl == null)
    }

    @Test
    fun theSearchBoxFindsByNameThroughTheMediaStore() {
        show()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("totum-phone-video").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(PHONE_SEARCH_TAG).performTextInput("zzznomatch")
        compose.waitUntil(
            15_000
        ) { compose.onAllNodesWithText(text(R.string.phone_empty_search)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(text(R.string.phone_empty_search)).assertIsDisplayed()
    }

    @Test
    fun theTabsListPhotosAndAPhotoOpensInTheViewerWithASlideshow() {
        show()
        compose.onNodeWithText(text(R.string.phone_tab_photos)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(PHOTO_GRID_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        compose.onNodeWithTag(PHOTO_GRID_TAG).performScrollToNode(hasContentDescription("totum-phone-red"))
        compose.onNodeWithContentDescription("totum-phone-red").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(PHOTO_VIEWER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(PHOTO_VIEWER_TAG).assertIsDisplayed()

        compose.onNodeWithTag(PHOTO_SLIDESHOW_TAG).performClick()
        compose.onNodeWithText(text(R.string.photo_slideshow_stop)).assertIsDisplayed()
        compose.onNodeWithTag(PHOTO_SLIDESHOW_TAG).performClick()
        compose.onNodeWithText(text(R.string.photo_slideshow_start)).assertIsDisplayed()

        compose.onNodeWithTag(PHOTO_CLOSE_TAG).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(PHOTO_VIEWER_TAG).fetchSemanticsNodes().isEmpty() }
    }

    private fun openFirstPhotoInTheViewer() {
        compose.onNodeWithText(text(R.string.phone_tab_photos)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(PHOTO_GRID_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(PHOTO_GRID_TAG).performScrollToNode(hasContentDescription("totum-phone-blue"))
        compose.onNodeWithContentDescription("totum-phone-blue").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(PHOTO_VIEWER_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun withNoScreenOpenTheViewerSaysHowToGetOne() {
        show()
        openFirstPhotoInTheViewer()

        compose.onNodeWithTag(PHOTO_SCREEN_NONE_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(PHOTO_SCREEN_BUTTON_TAG).assertCountEquals(0)
    }

    @Test
    fun aPhotoIsShownOnTheChosenScreenAndSwipingShowsTheNextUntilStopped() {
        val container = show()
        val playback = container.playbackController as FakePlaybackController
        playback.screensFlow.value = ScreenTargets(listOf(ScreenTarget("pc1", "Dewi's PC")))
        openFirstPhotoInTheViewer()

        compose.onNodeWithTag(PHOTO_SCREEN_BUTTON_TAG).performClick()
        compose.onNodeWithTag("$PHOTO_SCREEN_ITEM_TAG-pc1").performClick()
        compose.waitUntil(15_000) { playback.shownOnScreens.isNotEmpty() }
        val first = playback.shownOnScreens.first()
        assertEquals("pc1", first.first)
        assertTrue("the picture sent was ${first.second}", first.second.endsWith(".jpg"))
        assertEquals("totum-phone-blue", first.third)

        compose.onNodeWithTag(PHOTO_VIEWER_TAG).performTouchInput { swipeLeft() }
        compose.waitUntil(15_000) { playback.shownOnScreens.size >= 2 }
        assertTrue(playback.shownOnScreens.last().third != "totum-phone-blue")

        compose.onNodeWithTag(PHOTO_SCREEN_STOP_TAG).performClick()
        compose.waitForIdle()
        assertEquals(listOf("pc1"), playback.clearedScreens)
        val shown = playback.shownOnScreens.size
        compose.onNodeWithTag(PHOTO_VIEWER_TAG).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(shown, playback.shownOnScreens.size)
    }

    @Test
    fun whenTheScreenGoesAwayThePhotosStopGoingToIt() {
        val container = show()
        val playback = container.playbackController as FakePlaybackController
        playback.screensFlow.value = ScreenTargets(listOf(ScreenTarget("pc1", "Dewi's PC")))
        openFirstPhotoInTheViewer()
        compose.onNodeWithTag(PHOTO_SCREEN_BUTTON_TAG).performClick()
        compose.onNodeWithTag("$PHOTO_SCREEN_ITEM_TAG-pc1").performClick()
        compose.waitUntil(15_000) { playback.shownOnScreens.isNotEmpty() }

        playback.screensFlow.value = ScreenTargets()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(PHOTO_SCREEN_NONE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        val shown = playback.shownOnScreens.size
        compose.onNodeWithTag(PHOTO_VIEWER_TAG).performTouchInput { swipeLeft() }
        compose.waitForIdle()

        assertEquals(shown, playback.shownOnScreens.size)
    }

    @Test
    fun withNothingGrantedAndNeverAskedTheTabOffersToAsk() {
        show(access(asked = false))

        compose.onNodeWithTag(PHONE_ALLOW_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phone_ask_title)).assertIsDisplayed()
    }

    @Test
    fun withNothingGrantedAfterAskingTheTabPointsAtSettings() {
        show(access(asked = true))

        compose.onNodeWithTag(PHONE_SETTINGS_TAG).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(PHONE_ALLOW_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun partialAccessSaysOnlyTheChosenPhotosAndVideosAreShown() {
        show(access(PhoneMediaPermissions.READ_SELECTED, asked = true))

        compose.onNodeWithText(text(R.string.phone_partial_banner)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.phone_partial_more)).assertIsDisplayed()
    }

    @Test
    fun audioIsBlockedIndependentlyWhenOnlyPartialPhotoAccessWasGiven() {
        show(access(PhoneMediaPermissions.READ_SELECTED, asked = true))

        compose.onNodeWithText(text(R.string.phone_tab_audio)).performClick()

        compose.onNodeWithTag(PHONE_SETTINGS_TAG).assertIsDisplayed()
    }

    @Test
    fun theGoogleTabSaysItNeedsSetupWhenThisBuildHasNoGoogleClient() {
        show()

        compose.onNodeWithText(text(R.string.phone_tab_google)).performClick()

        compose.onNodeWithTag(GOOGLE_CARD_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.google_setup_title)).assertIsDisplayed()
    }
}
