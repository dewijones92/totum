package com.dewijones92.totum.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.targets.DeviceMark
import com.dewijones92.totum.targets.InMemoryPlayOn
import com.dewijones92.totum.targets.InMemoryTargetColours
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.DEVICE_MARK_TAG
import com.dewijones92.totum.ui.common.DeviceMarkBadge
import com.dewijones92.totum.ui.common.LocalDeviceMarks
import com.dewijones92.totum.ui.queue.ADD_BROWSER_HOW_TAG
import com.dewijones92.totum.ui.queue.ADD_BROWSER_TAG
import com.dewijones92.totum.ui.queue.PLAY_ON_OPTION_PREFIX
import com.dewijones92.totum.ui.queue.PLAY_ON_ROW_TAG
import com.dewijones92.totum.ui.queue.PLAY_ON_UNREACHABLE_TAG
import com.dewijones92.totum.ui.queue.PlayOnRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PlayOnPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private class Pi(var targets: List<TargetState>, var address: String? = null) : TargetsApi {
        val controlled = mutableListOf<Pair<String, TargetControl>>()
        var down = false
        override suspend fun targets(): HomeAnswer<TargetsAnswer> =
            if (down) HomeAnswer.No("down") else HomeAnswer.Ok(TargetsAnswer(targets, address))
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
            controlled += id to control
            return HomeAnswer.No("unused")
        }
    }

    private val asleepTv = TargetState(Target("lg-tv", "LG TV", TargetKind.TV), power = TargetPower.ASLEEP)
    private val soundbar = TargetState(Target("bar", "Soundbar", TargetKind.SPEAKER))
    private val pi = Pi(listOf(asleepTv, soundbar))
    private val store = InMemoryPlayOn()

    private fun repository(unmetered: Boolean = true) = TargetsRepository(
        pi,
        { Requester("me", "My phone") },
        InMemoryTargetColours(),
        scope,
        playOn = store,
        unmetered = { unmetered },
    )

    private fun show(repo: TargetsRepository) {
        compose.setContent { TotumTheme { PlayOnRow(repo, padStatusBar = false) } }
        compose.waitForIdle()
    }

    @Test
    fun atHomeEveryDeviceIsListedEvenSwitchedOffAndPickingASleepingOneWakesIt() {
        val repo = repository()
        show(repo)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("📱 This phone ▾").performClick()

        compose.onNodeWithTag(PLAY_ON_OPTION_PREFIX + "lg-tv").assertIsDisplayed()
        compose.onNodeWithTag(PLAY_ON_OPTION_PREFIX + "bar").assertIsDisplayed()
        compose.onNodeWithTag(PLAY_ON_OPTION_PREFIX + "lg-tv").performClick()
        compose.waitUntil(WAIT_MS) {
            pi.controlled.isNotEmpty() && repo.selected.value == "lg-tv" && store.load() == "lg-tv"
        }

        assertEquals("lg-tv", repo.selected.value)
        assertEquals("lg-tv", store.load())
        assertEquals("lg-tv" to TargetAction.WAKE, pi.controlled.first().let { it.first to it.second.action })
        compose.onNodeWithText("📺 LG TV 💤 ▾").assertIsDisplayed()
    }

    @Test
    fun aDeviceSwitchedOnByHandSaysSoAndASleepingTvSaysPickingWakesIt() {
        val mic = TargetState(
            Target("mic", "Soundmic", TargetKind.SPEAKER),
            power = TargetPower.ASLEEP,
            switchedOnByHand = true,
        )
        pi.targets = listOf(asleepTv, mic)
        val repo = repository()
        show(repo)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("📱 This phone ▾").performClick()

        compose.onNodeWithText("🔌 Off · switch it on by hand").assertIsDisplayed()
        compose.onNodeWithText("💤 Off · picking it switches it on").assertIsDisplayed()
        TestShots.take("picker-by-hand-hint")
        compose.onNodeWithTag(PLAY_ON_OPTION_PREFIX + "mic").performClick()
        compose.waitUntil(WAIT_MS) { repo.selected.value == "mic" && store.load() == "mic" }

        assertEquals("mic", repo.selected.value)
        assertEquals(0, pi.controlled.size)
    }

    @Test
    fun whenThePiStopsAnsweringTheRowSaysHomeDevicesAreUnreachableInsteadOfVanishing() {
        val repo = repository()
        show(repo)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        pi.down = true

        runBlocking {
            repo.refresh("test: first failure")
            repo.refresh("test: second failure")
        }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithTag(PLAY_ON_UNREACHABLE_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(PLAY_ON_UNREACHABLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("🏠 Home devices unreachable").assertIsDisplayed()
        TestShots.take("picker-home-unreachable")
    }

    @Test
    fun theAddressToTypeIntoABrowserIsOneTapAwayEvenWithNoDeviceListed() {
        pi.targets = emptyList()
        pi.address = "192.168.0.5:8771"
        val repo = repository()
        show(repo)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("📱 This phone ▾").performClick()

        compose.onNodeWithTag(ADD_BROWSER_TAG).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(ADD_BROWSER_HOW_TAG).fetchSemanticsNodes().size)
        compose.onNodeWithTag(ADD_BROWSER_TAG).performClick()
        compose.waitForIdle()
        TestShots.take("picker-add-a-browser")

        compose.onNodeWithTag(ADD_BROWSER_HOW_TAG).assertIsDisplayed()
        compose.onNodeWithText("192.168.0.5:8771", substring = true).assertIsDisplayed()
        compose.onNodeWithText("📋 Copy address").assertIsDisplayed()
        compose.onNodeWithText("✅ Done").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(ADD_BROWSER_HOW_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun withNoDevicesAndNoAddressThereIsNoRowAtAll() {
        pi.targets = emptyList()
        val repo = repository()
        show(repo)
        Thread.sleep(SETTLE_MS)

        assertEquals(0, compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun awayFromHomeThereIsNothingToPickAndNoRow() {
        val repo = repository(unmetered = false)
        show(repo)
        Thread.sleep(SETTLE_MS)

        assertEquals(0, compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().size)
        assertNull(repo.selected.value)
    }

    @Test
    fun anItemPlayingOnADeviceWearsThatDevicesMark() {
        val marks = mutableStateOf(mapOf("a" to DeviceMark("lg-tv", "📺 LG TV", 0xFF0089B0L)))
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalDeviceMarks provides marks) {
                    Column {
                        DeviceMarkBadge(MediaItemId("a"))
                        DeviceMarkBadge(MediaItemId("other"))
                    }
                }
            }
        }

        compose.onNodeWithText("📺 LG TV").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithTag(DEVICE_MARK_TAG).fetchSemanticsNodes().size)
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 500L
    }
}
