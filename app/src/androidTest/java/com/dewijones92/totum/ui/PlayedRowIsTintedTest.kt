package com.dewijones92.totum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import com.dewijones92.totum.ui.common.pillarRowTint
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A finished row wears a cyan wash over its pillar's, and an unfinished one wears its pillar's alone.
 *
 * Dewi, 2026-09-21: *"any played item I want the background color of it to have a tinge of a
 * colour"* — cyan, from the app's own palette, at his choosing. Dewi, 2026-09-24: every row is also
 * tinted by pillar — a faint peach for a video, a faint lemon for a podcast — with the cyan layered on
 * top, so a played row still reads as played.
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
     * Both themes, because a wash defined as an alpha over a surface can read as a colour in one and
     * as grey in the other — and neither the alpha nor the tone is shared between them. The light
     * theme washes Cyan40 at 8% over Sand99; the dark washes Cyan80 at 14% over Sand10. The first
     * version used one alpha for both and had to be corrected by *looking* at it, which is why the
     * blue-gain bar below is not "any gain at all".
     */
    @Test
    fun `a played row is tinted and an unplayed one is not - dark theme`() {
        assertTinted(darkTheme = true)
    }

    private fun assertTinted(darkTheme: Boolean) {
        var surface = Color.Unspecified
        var podcastWash = Color.Unspecified
        var videoWash = Color.Unspecified
        composeTestRule.setContent {
            TotumTheme(darkTheme = darkTheme) {
                surface = MaterialTheme.colorScheme.surface
                podcastWash = pillarRowTint(MediaKind.PODCAST).compositeOver(surface)
                videoWash = pillarRowTint(MediaKind.VIDEO).compositeOver(surface)
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    TestRow(PLAYED, PlayState.Played)
                    TestRow(UNPLAYED, PlayState.Unplayed)
                    TestRow(UNPLAYED_VIDEO, PlayState.Unplayed, MediaKind.VIDEO)
                    // Part-way, which Dewi decided should NOT be tinted: it already carries the
                    // progress sliver, and tinting it too would leave nothing untinted to compare
                    // against. Asserted because a decision nothing pins is a decision that drifts.
                    TestRow(PART_WAY, PlayState.InProgress(positionMs = 30_000, durationMs = 120_000))
                }
            }
        }

        val played = cornerOf(PLAYED)
        val unplayed = cornerOf(UNPLAYED)
        val theme = if (darkTheme) "dark" else "light"

        // The control: an unplayed row is exactly its pillar's wash over the surface, so "everything is
        // tinted cyan" and "nothing is tinted" are distinguishable outcomes rather than one pass.
        assertTrue(
            "$theme: an unplayed podcast row should be the lemon wash $podcastWash — was $unplayed",
            unplayed.isNear(podcastWash, PILLAR_TOLERANCE),
        )
        assertTrue(
            "$theme: an unplayed video row should be the peach wash $videoWash — was ${cornerOf(UNPLAYED_VIDEO)}",
            cornerOf(UNPLAYED_VIDEO).isNear(videoWash, PILLAR_TOLERANCE),
        )
        assertTrue(
            "$theme: a video row and a podcast row should not look alike — $videoWash vs $podcastWash",
            !videoWash.isNear(podcastWash, PILLAR_TOLERANCE),
        )
        assertTrue(
            "$theme: a played row ($played) should differ from an unplayed one ($unplayed)",
            !played.isNear(unplayed),
        )
        assertTrue(
            "$theme: a part-way row must not be tinted — was ${cornerOf(PART_WAY)} against $unplayed",
            cornerOf(PART_WAY).isNear(unplayed),
        )

        // Cyan, not just "different": blue must gain on red, which rules out a grey wash and rules
        // out the tangerine hero (whose effect on these two channels is the opposite way round).
        val bluerBy = (played.blue - played.red) - (unplayed.blue - unplayed.red)
        assertTrue(
            "$theme: the wash should be cyan — blue gained only $bluerBy over $unplayed",
            bluerBy > MIN_BLUE_GAIN,
        )

        // And a TINGE: a wash the text cannot be read through is not what was asked for. Both washes
        // together must stay near the surface, so either one creeping up to a fill fails here.
        assertTrue(
            "$theme: the washes should stay faint — $played is far from $surface",
            maxOf(
                abs(played.red - surface.red),
                abs(played.green - surface.green),
                abs(played.blue - surface.blue),
            ) < MAX_SHIFT,
        )
    }

    @Composable
    private fun TestRow(tag: String, playState: PlayState, pillar: MediaKind = MediaKind.PODCAST) {
        MediaItemRow(
            item = item,
            subtitleLines = listOf("🎙️ The Rest Is Politics", "🏷️ Goalhanger"),
            pillar = pillar,
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
    private fun Color.isNear(other: Color, tolerance: Float = TOLERANCE): Boolean =
        abs(red - other.red) <= tolerance &&
            abs(green - other.green) <= tolerance &&
            abs(blue - other.blue) <= tolerance

    private companion object {
        const val PLAYED = "row-played"
        const val UNPLAYED = "row-unplayed"
        const val UNPLAYED_VIDEO = "row-unplayed-video"
        const val PART_WAY = "row-part-way"
        const val SAMPLE_INSET = 2
        const val TOLERANCE = 1f / 255f
        const val PILLAR_TOLERANCE = 2f / 255f
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
