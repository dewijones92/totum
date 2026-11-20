package com.dewijones92.totum.ui.queue

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.SeekSteps
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.AppShell
import com.dewijones92.totum.ui.TestShots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TargetBarAcrossTabsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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

    private fun item(id: String, title: String) = BackupItem(
        id,
        "feed",
        title,
        mediaUrl = "https://example.com/$id.mp3",
        contentKind = "STANDARD",
        playbackType = "AUDIO",
    )

    private val lg = TargetState(
        Target("lg-tv", "LG TV", TargetKind.TV),
        entries = listOf(TargetEntry(item("a", "Episode one")), TargetEntry(item("b", "Episode two"))),
        at = 0,
        playing = true,
        linked = true,
        positionMs = 125_000,
        durationMs = 600_000,
    )
    private val soundbar = TargetState(Target("bar", "Soundbar", TargetKind.SPEAKER))

    private fun onThePhone() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("phone-item"),
            sourceId = SourceId("s"),
            title = "On the phone",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/phone.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    private fun start(pi: Pi, select: String? = "lg-tv", phonePlays: Boolean = true): FakeAppContainer {
        val container = FakeAppContainer(targetsApi = pi)
        if (phonePlays) container.playbackQueue.playAll(listOf(onThePhone()))
        compose.setContent { TotumTheme { AppShell(container) } }
        compose.waitUntil(WAIT_MS) { container.targets.options.value.size > 1 }
        if (select != null) {
            container.targets.select(select)
            compose.waitUntil(WAIT_MS) { container.targets.selected.value == select }
        }
        compose.waitForIdle()
        return container
    }

    private fun tab(label: Int) = compose.onNodeWithText(context.getString(label)).performClick()

    private fun barCount() = compose.onAllNodesWithTag(TARGET_BAR_TAG).fetchSemanticsNodes().size

    private fun phoneSkipCount() =
        compose.onAllNodesWithContentDescription(context.getString(R.string.skip_to_next)).fetchSemanticsNodes().size

    @Test
    fun aChosenDeviceThatIsPlayingReplacesThePhonesBarOnEveryTab() {
        val container = start(Pi(listOf(lg, soundbar)), select = null)
        assertEquals("before a device is chosen the phone's own bar is there", 0, barCount())
        assertTrue(phoneSkipCount() > 0)

        container.targets.select("lg-tv")
        compose.waitForIdle()
        listOf(R.string.destination_videos, R.string.destination_library, R.string.destination_queue).forEach { tab ->
            tab(tab)
            compose.waitForIdle()
            assertEquals("the device's bar is on the tab $tab", 1, barCount())
            assertEquals("the phone's own bar is gone on the tab $tab", 0, phoneSkipCount())
        }

        container.targets.select(null)
        compose.waitForIdle()
        assertEquals(0, barCount())
        assertTrue(phoneSkipCount() > 0)
    }

    @Test
    fun theBarNamesTheDeviceItIsDrivingInItsOwnColour() {
        val container = start(Pi(listOf(lg, soundbar)))
        tab(R.string.destination_videos)
        compose.waitForIdle()

        compose.onNodeWithTag(TARGET_BAR_DEVICE_TAG).assertIsDisplayed()
        compose.onNodeWithText("📺 LG TV · ▶️ Playing").assertIsDisplayed()
        compose.onNodeWithText("Episode one").assertIsDisplayed()
        val colour = TargetColours.of(container.targets.colours.value, "lg-tv")
        assertTrue("the device has a colour of its own, not the phone's", colour != TargetColours.PHONE)
        TestShots.take("target-bar-videos-tab")
    }

    @Test
    fun theBarsButtonsDriveTheDeviceAndSeekByTheSharedSteps() {
        val pi = Pi(listOf(lg, soundbar))
        start(pi)
        tab(R.string.destination_videos)
        compose.waitForIdle()

        compose.onNodeWithTag(TARGET_BAR_PLAY_TAG).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.target_seek_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.target_seek_forward)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.target_next)).performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.size == 4 }

        assertEquals(
            listOf(TargetAction.PAUSE, TargetAction.SEEK, TargetAction.SEEK, TargetAction.NEXT),
            pi.controls.map { it.action },
        )
        assertEquals(125_000 - SeekSteps.BACK_MS, pi.controls[1].positionMs)
        assertEquals(125_000 + SeekSteps.FORWARD_MS, pi.controls[2].positionMs)
    }

    @Test
    fun tappingTheBarOpensThatDevicesRemotePage() {
        val container = start(Pi(listOf(lg, soundbar)))
        tab(R.string.destination_videos)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(REMOTE_HEADING_TAG).fetchSemanticsNodes().size)

        compose.onNodeWithTag(TARGET_BAR_TAG).performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(REMOTE_HEADING_TAG).fetchSemanticsNodes().isNotEmpty() }

        assertEquals("lg-tv", container.targets.selected.value)
    }

    @Test
    fun aChosenDeviceWithNothingToShowLeavesThePhonesBarWhereItIs() {
        start(Pi(listOf(lg.copy(entries = emptyList(), at = -1, playing = false), soundbar)))

        assertEquals(0, barCount())
        assertTrue(phoneSkipCount() > 0)
    }

    @Test
    fun theQueueTabShowsTheSamePlayStateInTheBarAndOnThePage() {
        val pi = Pi(listOf(lg.copy(playing = false), soundbar))
        start(pi)
        tab(R.string.destination_queue)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(REMOTE_PLAY_TAG).fetchSemanticsNodes().isNotEmpty() }

        val play = context.getString(R.string.target_play)
        val shown = compose.onAllNodesWithContentDescription(play).fetchSemanticsNodes().size
        assertEquals("the bar and the page both offer Play, and neither offers Pause: $shown", 2, shown)
        assertEquals(0, compose.onAllNodesWithContentDescription(context.getString(R.string.target_pause)).fetchSemanticsNodes().size)
        TestShots.take("target-bar-queue-tab")
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
