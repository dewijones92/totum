package com.dewijones92.totum.ui.queue

import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.AppShell
import com.dewijones92.totum.ui.TestShots
import com.dewijones92.totum.ui.common.LocalOpenAsk
import com.dewijones92.totum.ui.common.LocalOpenSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TheQueueTabSpendsItsHeightOnTheListTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Pi(val targets: List<TargetState>) : TargetsApi {
        override suspend fun targets() = HomeAnswer.Ok(TargetsAnswer(targets))
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
    }

    private val onTheirQueue =
        listOf(TargetEntry(BackupItem("a", "feed", "Bluey", contentKind = "STANDARD", playbackType = "AUDIO")))
    private val lg = TargetState(Target("lg", "LG S65Q(A4)", TargetKind.SPEAKER), entries = onTheirQueue)
    private val mic = TargetState(Target("mic", "dprofy Soundmic", TargetKind.SPEAKER), entries = onTheirQueue)

    private fun queued(n: Int, kind: MediaContentKind = MediaContentKind.STANDARD) = PlayableItem(
        item = MediaItem(
            id = MediaItemId("row$n"),
            sourceId = SourceId("s"),
            title = "Row $n – Global Institutions Reeling After Microsoft Outage | Novara Live",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$n.mp3"),
            contentKind = kind,
        ),
        handle = PlayHandle.Podcast(),
    )

    private fun container(): FakeAppContainer {
        val container = FakeAppContainer(targetsApi = Pi(listOf(lg, mic)))
        container.playbackQueue.playAll(listOf(queued(1)))
        container.playbackQueue.enqueueAll((2..ROWS / 2).map(::queued), QueueGroup("album:g", "Origin Story"))
        container.playbackQueue.enqueueAll((ROWS / 2 + 1..ROWS).map { queued(it, MediaContentKind.MUSIC) })
        container.listeningSwitcher.start()
        return container
    }

    private fun openQueueTab(container: FakeAppContainer) {
        compose.setContent { TotumTheme { AppShell(container) } }
        compose.waitUntil(WAIT_MS) { container.targets.options.value.size > 1 }
        compose.onNodeWithText(context.getString(R.string.destination_queue)).performClick()
        compose.waitForIdle()
    }

    private fun search() = compose.onAllNodesWithContentDescription(context.getString(R.string.destination_search))

    @Test
    fun theShellsDeviceRowIsDrawnOnEveryTabButTheQueueWhichHasItsOwnChips() {
        val container = container()
        compose.setContent { TotumTheme { AppShell(container) } }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(context.getString(R.string.destination_queue)).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().size)
        compose.onNodeWithText("🔈 dprofy Soundmic").performClick()
        compose.waitForIdle()
        assertEquals("mic", container.targets.selected.value)

        compose.onNodeWithText(context.getString(R.string.destination_videos)).performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("🔈 dprofy Soundmic ▾").assertExists()
        compose.onNodeWithText(context.getString(R.string.destination_queue)).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(PLAY_ON_ROW_TAG).fetchSemanticsNodes().size)
        assertEquals("mic", container.targets.selected.value)
    }

    @Test
    fun theQueueListGetsTheHeightTheDeviceRowAndTheLoneSearchRowUsedToTake() {
        val container = container()
        openQueueTab(container)
        val density = compose.density.density
        val list = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.height }
        val listTop = list.boundsInRoot.top
        val listBottom = list.boundsInRoot.bottom
        val rows = compose.onAllNodes(hasText("Row ", substring = true)).fetchSemanticsNodes().map { it.boundsInRoot }
        val whole = rows.count { it.top >= listTop && it.bottom <= listBottom }
        val partly = rows.count { it.bottom > listTop && it.top < listBottom }
        val actions = compose.onAllNodes(
            hasClickAction()
        ).fetchSemanticsNodes().filter { it.boundsInRoot.top < listTop }
        val lines = actions.joinToString(" | ") {
            val label = it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "?"
            "$label@${(it.boundsInRoot.left / density).toInt()}-${(it.boundsInRoot.right / density).toInt()}" +
                "x${(it.boundsInRoot.top / density).toInt()}-${(it.boundsInRoot.bottom / density).toInt()}"
        }
        Log.i(
            "dewidebug",
            "queue-space listTopDp=${listTop / density} listBottomDp=${listBottom / density} " +
                "listHeightDp=${(listBottom - listTop) / density} rowsWhole=$whole rowsPartly=$partly " +
                "searchTopsDp=${search().fetchSemanticsNodes().map { it.boundsInRoot.top / density }}",
        )
        Log.i("dewidebug", "queue-space actions above the list: $lines")
        TestShots.take("queue-space")
        assertEquals("one search icon on the Queue tab", 1, search().fetchSemanticsNodes().size)
        assertTrue("a row of the list shows whole: $whole", whole >= 1)
    }

    @Test
    fun theQueueHeaderIsTwoLinesAtPhoneWidthAndSearchNeverStandsAloneOnALine() {
        val container = container()
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalOpenSearch provides {}, LocalOpenAsk provides {}) {
                    QueueScreen(container)
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val widthDp = compose.onRoot().fetchSemanticsNode().boundsInRoot.width / density
        val search = search().fetchSemanticsNodes().single().boundsInRoot
        val chips = compose.onNodeWithText(context.getString(R.string.target_chip_phone)).fetchSemanticsNode()
        val header = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.top > chips.boundsInRoot.bottom && it.top < search.bottom + 1f }
            .filter { it.height / density <= ACTION_MAX_DP }
        val lines = header.map { (it.top / density).toInt() }.distinct().sorted()
        val alone = header.count { (it.top / density).toInt() == (search.top / density).toInt() } == 1
        Log.i("dewidebug", "queue-header width=${widthDp}dp lines=$lines searchAlone=$alone")
        assertTrue("search is alone on its line at ${widthDp}dp: $lines", !alone)
        if (widthDp >= PHONE_WIDTH_DP) {
            assertTrue("the header is ${lines.size} lines at ${widthDp}dp: $lines", lines.size <= 2)
        }
    }

    private companion object {
        const val ROWS = 14
        const val WAIT_MS = 10_000L
        const val ACTION_MAX_DP = 60
        const val PHONE_WIDTH_DP = 411
    }
}
