package com.dewijones92.totum.exsurge

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.SilentWav
import com.dewijones92.totum.ui.player.FULL_PLAYER_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExsurgeBackTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val playback = (context as TotumApplication).container.playbackController

    @Test
    fun backFromTheExsurgeScreenOpensThePlayerWhenAnItemIsLoaded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch<MainActivity>(MainActivity.intent(context, openPlayer = false)).use {
            compose.waitUntil(10_000) { playback.player != null }
            val file = File(context.cacheDir, "exsurge-back.wav").apply { writeBytes(SilentWav.bytes(90)) }
            val item =
                MediaItem(
                    MediaItemId("exsurge-back"),
                    SourceId("test"),
                    "Back test",
                    publishedAt = null,
                    duration = null
                )
            instrumentation.runOnMainSync {
                playback.play(item, kind = MediaKind.PODCAST, localPath = file.absolutePath, startPositionMs = 0)
            }
            compose.waitUntil(10_000) { playback.state.value?.itemId == item.id }
            val fullPlayer = hasTestTag(FULL_PLAYER_TAG)
            ActivityScenario.launch<ExsurgeActivity>(ExsurgeActivity.intent(context, from = "test")).use { screen ->
                screen.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(10_000) { compose.onAllNodes(fullPlayer).fetchSemanticsNodes().isNotEmpty() }
            }
            instrumentation.runOnMainSync { playback.setPlaying(false) }
        }
    }
}
