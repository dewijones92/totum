package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.data.targets.VolumeMode
import com.dewijones92.totum.targets.ContextTargetWords
import com.dewijones92.totum.targets.InMemoryTargetColours
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.REMOTE_CARD_TAG
import com.dewijones92.totum.ui.queue.REMOTE_CONTROLS_TAG
import com.dewijones92.totum.ui.queue.REMOTE_HEADING_TAG
import com.dewijones92.totum.ui.queue.REMOTE_PLAY_TAG
import com.dewijones92.totum.ui.queue.REMOTE_QUEUE_HEADING_TAG
import com.dewijones92.totum.ui.queue.REMOTE_ROW_TAG
import com.dewijones92.totum.ui.queue.REMOTE_VOLUME_TAG
import com.dewijones92.totum.ui.queue.RemoteQueue
import com.dewijones92.totum.ui.queue.SWATCH_TAG_PREFIX
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RemoteQueueTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = mainThreadScope()

    @After
    fun stop() = scope.cancel()

    private class Pi(var targets: List<TargetState>) : TargetsApi {
        val controls = CopyOnWriteArrayList<TargetControl>()
        override suspend fun targets() = HomeAnswer.Ok(TargetsAnswer(targets))
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
            controls += control
            return HomeAnswer.No("unused")
        }
    }

    private fun item(id: String, title: String) =
        BackupItem(
            id,
            "feed",
            title,
            mediaUrl = "https://example.com/$id.mp3",
            contentKind = "STANDARD",
            playbackType = "AUDIO",
        )

    private val tv = TargetState(
        Target("lg-tv", "LG TV", TargetKind.TV),
        entries = listOf(TargetEntry(item("a", "Episode one")), TargetEntry(item("b", "Episode two"))),
        at = 0,
        playing = true,
        positionMs = 125_000,
        durationMs = 600_000,
    )

    private fun show(state: TargetState): Pi {
        val pi = Pi(listOf(state))
        val repo = TargetsRepository(
            pi,
            { Requester("me", "My phone") },
            InMemoryTargetColours(),
            scope,
            words = ContextTargetWords(context),
        )
        compose.setContent { TotumTheme { RemoteQueue(repo, "lg-tv") } }
        runBlocking { repo.refresh("test") }
        compose.waitUntil(WAIT_MS) { repo.targets.value.isNotEmpty() }
        compose.waitForIdle()
        hold()
        return pi
    }

    private fun hold() {
        val ms = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull() ?: 0
        if (ms > 0) Thread.sleep(ms)
    }

    @Test
    fun theSeekRowSendsASeekFromWhereTheTargetIs() {
        val pi = show(tv)

        compose.onNodeWithContentDescription(context.getString(R.string.target_seek_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.target_seek_forward)).performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.size == 2 }

        assertEquals(listOf(TargetAction.SEEK, TargetAction.SEEK), pi.controls.map { it.action })
        assertEquals(listOf(115_000L, 155_000L), pi.controls.map { it.positionMs })
    }

    @Test
    fun tappingThePlayingRowDoesNotRestartItAndAnotherRowJumpsByItsId() {
        val pi = show(tv)

        compose.onNodeWithTag("$REMOTE_ROW_TAG-0").performClick()
        Thread.sleep(SETTLE_MS)
        assertTrue("the playing row sent ${pi.controls}", pi.controls.isEmpty())

        compose.onNodeWithTag("$REMOTE_ROW_TAG-1").performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.isNotEmpty() }

        assertEquals(TargetAction.JUMP, pi.controls.single().action)
        assertEquals("b", pi.controls.single().itemId)
        assertEquals(1, pi.controls.single().index)
    }

    @Test
    fun aPausedDeckPlaysWhenItsCurrentRowIsTapped() {
        val pi = show(tv.copy(playing = false))

        compose.onNodeWithTag("$REMOTE_ROW_TAG-0").performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.isNotEmpty() }

        assertEquals(TargetAction.PLAY, pi.controls.single().action)
    }

    @Test
    fun theProblemIsInThePhonesOwnWords() {
        show(tv.copy(playing = false, trouble = TargetTrouble(TroubleKind.NOT_CONNECTED), problem = "plain Pi text"))

        compose.onNodeWithText("📶 Couldn't reach LG TV: it isn't connected (off or out of range)").assertIsDisplayed()
    }

    @Test
    fun theHeadingNamesTheDeviceAndItsLinkStateInTheScreensOwnTextColour() {
        show(tv.copy(linked = true))

        compose.onNodeWithTag(REMOTE_HEADING_TAG).assertTextEquals("▶️ Playing on LG TV · 🟢 Linked")
        TestShots.take("remote-page")
    }

    @Test
    fun aDeviceThatIsAsleepSaysSoInItsHeading() {
        show(tv.copy(playing = false, power = TargetPower.ASLEEP))

        compose.onNodeWithTag(REMOTE_HEADING_TAG).assertTextEquals("⏸️ Paused on LG TV · 💤 Off")
    }

    @Test
    fun theNowPlayingCardIsOneItemOfTheListAndLeavesMostOfTheScreenToTheQueue() {
        show(tv)

        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        val card = compose.onNodeWithTag(REMOTE_CARD_TAG).fetchSemanticsNode().boundsInRoot.height
        val controls = compose.onNodeWithTag(REMOTE_CONTROLS_TAG).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("$REMOTE_ROW_TAG-0").fetchSemanticsNode().boundsInRoot
        assertTrue("the card takes ${card / screen} of the screen", card / screen < MAX_CARD_SHARE)
        assertTrue("the controls row is under the card, not beside it", controls.top >= 0)
        assertTrue("a queue row is on screen with the card", row.bottom <= screen)
    }

    @Test
    fun repeatSleepSilencesBoostAndColourAreBehindOneControlsRowAndOneKindOfTile() {
        show(tv)
        assertEquals(0, compose.onAllNodesWithText(text(R.string.repeat), substring = true).fetchSemanticsNodes().size)

        compose.onNodeWithTag(REMOTE_CONTROLS_TAG).performClick()
        compose.waitForIdle()

        listOf(R.string.repeat, R.string.skip_silence, R.string.volume_boost, R.string.sleep_timer).forEach { label ->
            compose.onNodeWithContentDescription(text(label)).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(text(R.string.target_colour)).assertIsDisplayed()
        TestShots.take("remote-page-controls")
    }

    @Test
    fun theColourSwatchesAreBigEnoughToHit() {
        show(tv)
        compose.onNodeWithTag(REMOTE_CONTROLS_TAG).performClick()
        compose.onNodeWithContentDescription(text(R.string.target_colour)).performClick()
        compose.waitForIdle()

        val density = compose.density.density
        TargetColours.palette.indices.forEach { index ->
            val box = compose.onNodeWithTag(SWATCH_TAG_PREFIX + index).fetchSemanticsNode().boundsInRoot
            assertTrue("swatch $index is ${box.width / density}dp wide", box.width / density >= MIN_SWATCH_DP)
            assertTrue("swatch $index is ${box.height / density}dp tall", box.height / density >= MIN_SWATCH_DP)
        }
    }

    @Test
    fun theQueueIsHeadedWithTheDeviceAndHowManyItemsItHolds() {
        show(tv)

        compose.onNodeWithTag(REMOTE_QUEUE_HEADING_TAG).assertTextEquals("📋 Up next on LG TV · 2")
    }

    @Test
    fun theVolumeLineIsAnIconAPercentageAndAShortLabelWithTheReasonBehindAnInfoTap() {
        show(tv.copy(volume = 54, volumeMode = VolumeMode.HARDWARE))

        compose.onNodeWithTag(REMOTE_VOLUME_TAG).assertTextContains("54%")
        compose.onNodeWithTag(REMOTE_VOLUME_TAG).assertTextContains("Speaker")
        assertEquals(
            "the long reason is not on the line",
            0,
            compose.onAllNodesWithText(text(R.string.target_volume_why_speaker)).fetchSemanticsNodes().size,
        )

        compose.onNodeWithContentDescription(text(R.string.target_volume_why)).performClick()

        compose.onNodeWithText(text(R.string.target_volume_why_speaker)).assertIsDisplayed()
    }

    @Test
    fun theSoftwareVolumeSaysWhyItIsNotTheSpeakersOwn() {
        show(tv.copy(volume = 30, volumeMode = VolumeMode.SOFTWARE))

        compose.onNodeWithTag(REMOTE_VOLUME_TAG).assertTextContains("Totum")
        compose.onNodeWithContentDescription(text(R.string.target_volume_why)).performClick()
        compose.onNodeWithText(text(R.string.target_volume_why_software)).assertIsDisplayed()
    }

    @Test
    fun theTransportHasOneFilledPlayAndAnIconOnlyStop() {
        val pi = show(tv)

        compose.onNodeWithTag(REMOTE_PLAY_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.target_stop)).performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.isNotEmpty() }

        assertEquals(TargetAction.STOP, pi.controls.single().action)
        assertEquals(
            "no emoji-and-word Stop button any more",
            0,
            compose.onAllNodesWithText("⏹️ " + text(R.string.target_stop)).fetchSemanticsNodes().size,
        )
    }

    private fun text(id: Int): String = context.getString(id)

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 600L
        const val MAX_CARD_SHARE = 0.5
        const val MIN_SWATCH_DP = 48
    }
}
