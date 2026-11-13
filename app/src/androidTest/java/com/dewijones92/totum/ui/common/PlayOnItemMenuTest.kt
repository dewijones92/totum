package com.dewijones92.totum.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.targets.InMemoryPlayOn
import com.dewijones92.totum.targets.InMemoryTargetColours
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.PLAY_ON_OPTION_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayOnItemMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private val pi = object : TargetsApi {
        override suspend fun targets() = HomeAnswer.Ok(
            TargetsAnswer(
                listOf(
                    TargetState(Target("lg-tv", "LG TV", TargetKind.TV), power = TargetPower.ASLEEP),
                    TargetState(Target("bar", "Soundbar", TargetKind.SPEAKER)),
                ),
            ),
        )

        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")

        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
    }

    private val repo = TargetsRepository(
        pi,
        { Requester("me", "My phone") },
        InMemoryTargetColours(),
        scope,
        playOn = InMemoryPlayOn(),
        unmetered = { true },
    )

    private val item = MediaItem(
        id = MediaItemId("ep1"),
        sourceId = SourceId("feed"),
        title = "Episode one",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://cdn.example.com/ep1.mp3"),
    )

    private val picked = mutableListOf<Pair<String, String>>()

    private val actions = object : ItemActions {
        override fun queue(items: List<MediaItem>, next: Boolean) = Unit
        override fun addToPlaylist(items: List<MediaItem>) = Unit
        override fun peek(item: MediaItem) = Unit
        override fun download(item: MediaItem, audioOnly: Boolean) = Unit
        override fun deleteDownload(id: MediaItemId) = Unit
        override fun setPlayed(id: MediaItemId, played: Boolean) = Unit
        override fun sourceLink(item: MediaItem): (() -> Unit)? = null
        override val audioMode: Boolean = true
        override fun switchMode(item: MediaItem) = Unit
        override val devices: TargetsRepository = repo
        override fun playOn(item: MediaItem, option: PlayOnOption) {
            picked += item.id.value to option.id
        }
    }

    @Test
    fun theItemMenuOffersEveryDeviceAndPickingOnePlaysThatItemThere() {
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalItemActions provides actions) {
                    MediaItemRow(item = item, subtitleLines = emptyList(), pillar = MediaKind.PODCAST, onPlay = {})
                }
            }
        }
        runBlocking { repo.refresh("test") }
        compose.waitUntil(WAIT_MS) { repo.options.value.size > 1 }
        compose.onNodeWithContentDescription(context.getString(R.string.queue_menu)).performClick()
        compose.onNodeWithTag(PLAY_ON_SHEET_TAG).performClick()

        compose.onNodeWithTag(PLAY_ON_OPTION_PREFIX + "lg-tv").assertIsDisplayed()
        compose.onNodeWithTag(PLAY_ON_OPTION_PREFIX + "bar").performClick()

        assertEquals(listOf("ep1" to "bar"), picked)
    }

    @Test
    fun withOnlyThisPhoneOnOfferTheItemMenuHasNoPlayOnEntry() {
        val awayRepo = TargetsRepository(
            pi,
            { Requester("me", "My phone") },
            InMemoryTargetColours(),
            scope,
            playOn = InMemoryPlayOn(),
            unmetered = { false },
        )
        runBlocking { awayRepo.refresh("test") }
        val awayActions = object : ItemActions by actions {
            override val devices: TargetsRepository = awayRepo
        }
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalItemActions provides awayActions) {
                    MediaItemRow(item = item, subtitleLines = emptyList(), pillar = MediaKind.PODCAST, onPlay = {})
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.queue_menu)).performClick()
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)

        assertEquals(0, compose.onAllNodesWithTag(PLAY_ON_SHEET_TAG).fetchSemanticsNodes().size)
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 500L
    }
}
