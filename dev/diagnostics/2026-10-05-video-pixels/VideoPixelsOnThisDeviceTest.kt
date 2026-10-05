package com.dewijones92.totum.ui

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.descendants
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.common.Diag
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

class VideoPixelsOnThisDeviceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val fake = FakePlaybackController()
    private lateinit var player: ExoPlayer
    private lateinit var container: FakeAppContainer
    private val firstFrame = AtomicBoolean()

    private fun start(hasVideo: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clip = File(instrumentation.targetContext.cacheDir, "rotation-clip.mp4")
        instrumentation.context.assets.open("rotation.mp4").use { input -> clip.outputStream().use(input::copyTo) }
        composeTestRule.runOnUiThread {
            val renderers = DefaultRenderersFactory(
                composeTestRule.activity
            ).setMediaCodecSelector { mime, secure, tunneled ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneled).sortedBy { it.hardwareAccelerated }
            }
            player = ExoPlayer.Builder(composeTestRule.activity, renderers).build().apply {
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.set(true) }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)))
                repeatMode = Player.REPEAT_MODE_ALL
                prepare()
                play()
            }
            val controller = object : PlaybackController by fake {
                override val player: Player get() = this@VideoPixelsOnThisDeviceTest.player
            }
            container = FakeAppContainer(playbackController = controller)
            fake.emitState(
                PlaybackState(
                    itemId = MediaItemId("rotation-clip"), title = "Rotation video", artist = "Totum",
                    artworkUrl = null, kind = if (hasVideo) MediaKind.VIDEO else MediaKind.PODCAST,
                    isPlaying = true, positionMs = 0, durationMs = 5_000, speed = 1f, hasVideo = hasVideo,
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

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    @Test
    fun videoPixelsAreVisibleBeforeRotating() {
        start(hasVideo = true)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.setContent {
                PlayerSurface(player, Modifier.fillMaxSize(), SURFACE_TYPE_SURFACE_VIEW)
            }
        }
        composeTestRule.waitUntil(ROTATION_TIMEOUT_MS) { firstFrame.get() }
        capture("rotation-baseline")
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
        instrumentation.runOnMainSync {
            val decor = composeTestRule.activity.window.decorView as ViewGroup
            val texture = decor.descendants.filterIsInstance<TextureView>().firstOrNull()
            Diag.log(
                "rotation-test",
                "$name: playing=${player.isPlaying} state=${player.playbackState} position=${player.currentPosition} " +
                    "accelerated=${decor.isHardwareAccelerated} texture=${texture?.width}x${texture?.height} " +
                    "available=${texture?.isAvailable} shown=${texture?.isShown} alpha=${texture?.alpha}",
            )
            texture?.bitmap?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "$name-texture.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        composeTestRule.waitUntil(ROTATION_TIMEOUT_MS) { patternIsVisible() }
        val file = File(instrumentation.targetContext.cacheDir, "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun patternIsVisible(): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Got it")
            ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val visible = (1..9).any { column ->
            val pixel = bitmap.getPixel(bitmap.width * column / 10, bitmap.height / 5)
            Color.green(pixel) > 80 && Color.red(pixel) < Color.green(pixel) / 2 &&
                Color.blue(pixel) < Color.green(pixel) / 2
        }
        bitmap.recycle()
        return visible
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
