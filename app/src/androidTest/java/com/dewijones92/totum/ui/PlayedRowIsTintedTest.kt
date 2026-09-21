package com.dewijones92.totum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.MediaItemRow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A finished row wears a cyan wash, and an unfinished one wears nothing.
 *
 * Dewi, 2026-09-21: *"any played item I want the background color of it to have a tinge of a
 * colour"* — cyan, from the app's own palette, at his choosing.
 *
 * Read off the PIXELS rather than asserted against the colour function, and that is the whole point
 * of putting this on a device. `playedRowTint` returning the right `Color` says nothing about
 * whether the row draws it: the modifier could be ordered behind the surface, applied to the wrong
 * node, or lost entirely, and a test of the function would stay green through all three. This repo
 * has shipped exactly that — eleven green queue-row tests over rows that rendered solid red
 * (cbf9916) — so the assertion is on what came out of the rasteriser.
 *
 * Sampled at the row's top-left, which is inside its 16dp/10dp padding: background only, with no
 * glyph, thumbnail or text anywhere near it.
 */
@RunWith(AndroidJUnit4::class)
class PlayedRowIsTintedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val item = MediaItem(
        id = MediaItemId("ep"),
        sourceId = SourceId("feed"),
        title = "Ep 214",
        publishedAt = null,
        duration = null,
        author = "The Rest Is Politics",
        publisher = "Goalhanger",
    )

    @Test
    fun `a played row is tinted and an unplayed one is not - light theme`() {
        assertTinted(darkTheme = false)
    }

    /**
     * Both themes, because a wash defined as an alpha over the surface can vanish in one of them:
     * the same cyan at 8% sits on Sand99 in the light theme and Sand10 in the dark, and this repo
     * has a memory of flipping the theme being what reveals a contrast bug.
     */
    @Test
    fun `a played row is tinted and an unplayed one is not - dark theme`() {
        assertTinted(darkTheme = true)
    }

    private fun assertTinted(darkTheme: Boolean) {
        var surface = Color.Unspecified
        composeTestRule.setContent {
            TotumTheme(darkTheme = darkTheme) {
                surface = MaterialTheme.colorScheme.surface
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    TestRow(PLAYED, PlayState.Played)
                    TestRow(UNPLAYED, PlayState.Unplayed)
                }
            }
        }

        val played = cornerOf(PLAYED)
        val unplayed = cornerOf(UNPLAYED)
        val theme = if (darkTheme) "dark" else "light"

        // The control: an unplayed row is the surface it sits on, so "everything is tinted" and
        // "nothing is" are distinguishable outcomes rather than one indistinguishable pass.
        assertTrue(
            "$theme: an unplayed row should be the plain surface — was $unplayed against $surface",
            unplayed.isNear(surface),
        )
        assertTrue(
            "$theme: a played row should differ from an unplayed one — both were $played",
            !played.isNear(unplayed)
        )

        // Cyan, not just "different": blue must gain on red, which rules out a grey wash and rules
        // out the tangerine hero (whose effect on these two channels is the opposite way round).
        val bluerBy = (played.blue - played.red) - (unplayed.blue - unplayed.red)
        assertTrue(
            "$theme: the wash should be cyan — blue gained only $bluerBy over $unplayed",
            bluerBy > MIN_BLUE_GAIN,
        )

        // And a TINGE: a wash the text cannot be read through is not what was asked for. 0.08 alpha
        // over the surface moves no channel far, so a wash that has crept up to a fill fails here.
        assertTrue(
            "$theme: the wash should stay faint — $played is far from $surface",
            maxOf(
                abs(played.red - surface.red),
                abs(played.green - surface.green),
                abs(played.blue - surface.blue),
            ) < MAX_SHIFT,
        )
    }

    @Composable
    private fun TestRow(tag: String, playState: PlayState) {
        MediaItemRow(
            item = item,
            subtitleLines = listOf("🎙️ The Rest Is Politics", "🏷️ Goalhanger"),
            pillar = MediaKind.PODCAST,
            onPlay = {},
            playState = playState,
            onDownload = null,
            onDeleteDownload = null,
            onPlayNext = null,
            onAddToQueue = null,
            onAddToPlaylist = null,
            onPeek = null,
            onSwitchMode = null,
            onGoToSource = null,
            onSetPlayed = null,
            modifier = Modifier.testTag(tag),
        )
    }

    private fun cornerOf(tag: String): Color {
        val pixels = composeTestRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        return pixels[SAMPLE_INSET, SAMPLE_INSET]
    }

    /** Equal to within a single 8-bit step, which is what a rasteriser is allowed to disagree by. */
    private fun Color.isNear(other: Color): Boolean =
        abs(red - other.red) <= TOLERANCE &&
            abs(green - other.green) <= TOLERANCE &&
            abs(blue - other.blue) <= TOLERANCE

    private companion object {
        const val PLAYED = "row-played"
        const val UNPLAYED = "row-unplayed"
        const val SAMPLE_INSET = 2
        const val TOLERANCE = 1f / 255f
        const val MAX_SHIFT = 0.2f

        /**
         * Enough blue gain to be a colour rather than a measurably-bluish grey. The dark theme
         * failed the eye at a bar of 0.01 while passing it numerically — 8% of cyan over Sand10 is
         * a lighter band — which is why the alpha is now luminance-aware and why this bar is not
         * set to "any gain at all".
         */
        const val MIN_BLUE_GAIN = 0.02f
    }
}
