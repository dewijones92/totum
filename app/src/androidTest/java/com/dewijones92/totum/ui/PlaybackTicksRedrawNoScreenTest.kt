package com.dewijones92.totum.ui

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.Recompositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackTicksRedrawNoScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val playing = PlaybackState(
        itemId = MediaItemId("tick"),
        title = "Ticking along",
        artist = "Someone",
        artworkUrl = null,
        isPlaying = true,
        positionMs = 1_000,
        durationMs = 600_000,
        speed = 1f,
        hasVideo = true,
    )

    private val screens = listOf("shell", "queue", "player", "stage", "dock")

    @Test
    fun positionTicksRedrawNoScreenWithTheQueueUnderThePlayer() {
        val controller = FakePlaybackController(player = composeTestRule.runOnUiThread { StubPlayer() })
        val container = FakeAppContainer(playbackController = controller)
        container.playbackQueue.playAll(listOf(queued("one"), queued("two"), queued("three")))
        composeTestRule.setContent { TotumTheme { AppShell(container) } }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { controller.emitState(playing) }
        composeTestRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeTestRule.onNodeWithText(context.getString(R.string.destination_queue)).performClick()
        composeTestRule.onAllNodesWithText(playing.title).onFirst().performClick()
        composeTestRule.waitForIdle()
        val before = screens.associateWith(Recompositions::of)
        assertTrue("the player never opened: $before", before.getValue("player") > 0)
        assertTrue("the video stage never composed: $before", before.getValue("stage") > 0)
        assertTrue("the queue never composed: $before", before.getValue("queue") > 0)

        repeat(TICKS) { tick ->
            composeTestRule.runOnIdle { controller.emitState(playing.copy(positionMs = 1_000L + (tick + 1) * 500)) }
            composeTestRule.waitForIdle()
        }

        assertEquals(before, screens.associateWith(Recompositions::of))
    }

    private fun queued(id: String) = PlayableItem(
        item = MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = "Queued $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
        ),
        handle = PlayHandle.Podcast(),
    )

    @androidx.annotation.OptIn(UnstableApi::class)
    private class StubPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.EMPTY)
            .build()
    }

    private companion object {
        const val TICKS = 5
    }
}
