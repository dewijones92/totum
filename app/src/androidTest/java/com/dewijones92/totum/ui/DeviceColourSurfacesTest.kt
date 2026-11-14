package com.dewijones92.totum.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.data.targets.PlayOn
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.targets.DeviceMarkRules
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.DeviceMarkBadge
import com.dewijones92.totum.ui.common.LocalDeviceMarks
import com.dewijones92.totum.ui.queue.PLAY_ON_DOT_PREFIX
import com.dewijones92.totum.ui.queue.PlayOnMenuItems
import com.dewijones92.totum.ui.queue.TargetChip
import com.dewijones92.totum.ui.queue.asColour
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DeviceColourSurfacesTest {

    @get:Rule
    val compose = createComposeRule()

    private val tv = Target("lg-tv", "LG TV", TargetKind.TV)
    private val browser = Target("browser-fire1", "Silk", TargetKind.BROWSER)
    private val options = listOf(PlayOnOption(PlayOn.phone), PlayOnOption(tv), PlayOnOption(browser))
    private val colours = mapOf(
        TargetColours.PHONE_ID to TargetColours.PHONE,
        tv.id to TargetColours.palette[0],
        browser.id to TargetColours.palette[3],
    )
    private val itemOn = mapOf(PlayOn.phone.id to "on-phone", tv.id to "on-tv", browser.id to "on-browser")

    private fun show() {
        val marks = DeviceMarkRules.marks(
            playingOn = mapOf("on-tv" to tv, "on-browser" to browser),
            options = options,
            colours = colours,
            phoneItemId = "on-phone",
        )
        val shown = mutableStateOf(marks)
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalDeviceMarks provides shown) {
                    Column {
                        PlayOnMenuItems(options, colours, currentId = tv.id) {}
                        options.forEach { option ->
                            Box(Modifier.testTag("chip-${option.id}")) {
                                TargetChip(
                                    option.target.label,
                                    TargetColours.of(colours, option.id).asColour(),
                                    selected = false,
                                    onClick = {},
                                )
                            }
                            Box(Modifier.testTag("badge-${option.id}")) {
                                DeviceMarkBadge(MediaItemId(itemOn.getValue(option.id)))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun Color.isNear(other: Color) =
        abs(red - other.red) <= TOLERANCE && abs(green - other.green) <= TOLERANCE &&
            abs(blue - other.blue) <= TOLERANCE

    private fun centre(tag: String): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun leftEdge(tag: String): Color {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[0, pixels.height / 2]
    }

    @Test
    fun aDeviceIsTheSameColourInThePickerDotTheQueueChipAndTheRowBadge() {
        show()
        TestShots.take("device-colour-surfaces")

        options.forEach { option ->
            val wanted = TargetColours.of(colours, option.id).asColour()
            val dot = centre(PLAY_ON_DOT_PREFIX + option.id)
            val chip = leftEdge("chip-${option.id}")
            val badge = leftEdge("badge-${option.id}")
            assertTrue("${option.id}: the picker dot is $dot, wanted $wanted", dot.isNear(wanted))
            assertTrue("${option.id}: the queue chip's edge is $chip, wanted $wanted", chip.isNear(wanted))
            assertTrue("${option.id}: the row badge's edge is $badge, wanted $wanted", badge.isNear(wanted))
        }
    }

    private companion object {
        const val TOLERANCE = 0.02f
    }
}
