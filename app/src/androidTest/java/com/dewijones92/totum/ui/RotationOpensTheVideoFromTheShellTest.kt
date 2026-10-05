package com.dewijones92.totum.ui

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.theme.TotumTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RotationOpensTheVideoFromTheShellTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val fake = FakePlaybackController()
    private lateinit var player: ExoPlayer
    private lateinit var container: FakeAppContainer
    private val firstFrame = AtomicBoolean()

    private fun start(hasVideo: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clip = File(instrumentation.targetContext.cacheDir, "rotation-clip.mp4")
        instrumentation.context.assets.open("clip.mp4").use { input -> clip.outputStream().use(input::copyTo) }
        composeTestRule.runOnUiThread {
            player = ExoPlayer.Builder(composeTestRule.activity).build().apply {
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.set(true) }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)))
                repeatMode = Player.REPEAT_MODE_ALL
                prepare()
                play()
            }
            val controller = object : PlaybackController by fake {
                override val player: Player get() = this@RotationOpensTheVideoFromTheShellTest.player
            }
            container = FakeAppContainer(playbackController = controller)
            fake.emitState(
                PlaybackState(
                    itemId = MediaItemId("rotation-clip"), title = "Rotation video", artist = "Totum",
                    artworkUrl = null, kind = if (hasVideo) MediaKind.VIDEO else MediaKind.PODCAST,
                    isPlaying = true, positionMs = 0, durationMs = 90_000, speed = 1f, hasVideo = hasVideo,
                ),
            )
            composeTestRule.activity.setContent { TotumTheme { AppShell(container, askForNotifications = {}) } }
        }
        rotate(UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT)
    }

    private fun rotate(requested: Int, expected: Int) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(requested)
        composeTestRule.waitUntil(ROTATION_TIMEOUT_MS) {
            composeTestRule.activity.resources.configuration.orientation == expected
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun rotatingTheMiniPlayerOpensImmersiveVideoAndReturningToPortraitRestoresThePage() {
        start(hasVideo = true)
        composeTestRule.onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
        rotate(UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE)
        composeTestRule.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed()
        composeTestRule.waitUntil(ROTATION_TIMEOUT_MS) { firstFrame.get() }
        capture("rotation-landscape")
        rotate(UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT)
        composeTestRule.onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Fullscreen").assertIsDisplayed()
        capture("rotation-portrait")
    }

    @Test
    fun rotatingAPodcastKeepsTheShell() {
        start(hasVideo = false)
        rotate(UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE)
        composeTestRule.onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Fullscreen").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Got it")
            ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val file = File(instrumentation.targetContext.cacheDir, "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        composeTestRule.runOnUiThread {
            if (::player.isInitialized) player.release()
            if (::container.isInitialized) container.applicationScope.cancel()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    private companion object {
        const val ROTATION_TIMEOUT_MS = 15_000L
    }
}
