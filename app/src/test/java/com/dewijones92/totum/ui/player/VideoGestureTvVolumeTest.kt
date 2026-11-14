package com.dewijones92.totum.ui.player

import com.dewijones92.totum.playback.CastVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGestureTvVolumeTest {

    private var tv: CastVolume? = CastVolume(level = 10, max = 20, muted = false)
    private val sentToTv = mutableListOf<Float>()

    private val state = VideoGestureState(
        activity = null,
        audio = null,
        tvVolume = { tv },
        setTvVolume = { sentToTv += it },
    )

    private fun volumeDrag(pixels: Float) = state.adjust(VideoAdjustment.VOLUME, pixels, STAGE_HEIGHT)

    @Test
    fun `beginning a volume drag while casting shows the TV's level and says it is the TV`() {
        state.begin(VideoAdjustment.VOLUME)

        assertEquals(VideoAdjustment.VOLUME, state.feedback?.kind)
        assertEquals(0.5f, state.feedback!!.fraction, TOLERANCE)
        assertTrue(state.feedback!!.onTv)
    }

    @Test
    fun `dragging up raises the TV and sends each new level to it`() {
        state.begin(VideoAdjustment.VOLUME)

        volumeDrag(-(STAGE_HEIGHT / 4))

        assertEquals(1, sentToTv.size)
        assertEquals(0.75f, sentToTv.single(), TOLERANCE)
        assertEquals(0.75f, state.feedback!!.fraction, TOLERANCE)
        assertTrue(state.feedback!!.onTv)
    }

    @Test
    fun `dragging down lowers the TV`() {
        state.begin(VideoAdjustment.VOLUME)

        volumeDrag(STAGE_HEIGHT / 5)

        assertEquals(0.3f, sentToTv.single(), TOLERANCE)
    }

    @Test
    fun `successive drags build on what the last one sent, not on the TV's old level`() {
        state.begin(VideoAdjustment.VOLUME)

        volumeDrag(-(STAGE_HEIGHT / 10))
        volumeDrag(-(STAGE_HEIGHT / 10))

        assertEquals(listOf(0.6f, 0.7f), sentToTv.map { Math.round(it * 100) / 100f })
    }

    @Test
    fun `the TV's level is clamped at both ends`() {
        state.begin(VideoAdjustment.VOLUME)

        repeat(5) { volumeDrag(-STAGE_HEIGHT) }
        assertEquals(1f, sentToTv.last(), TOLERANCE)

        repeat(5) { volumeDrag(STAGE_HEIGHT) }
        assertEquals(0f, sentToTv.last(), TOLERANCE)
    }

    @Test
    fun `with no TV connected a volume drag is the phone's and sends nothing to a TV`() {
        tv = null
        state.begin(VideoAdjustment.VOLUME)

        volumeDrag(-(STAGE_HEIGHT / 4))

        assertTrue(sentToTv.isEmpty())
        assertFalse(state.feedback!!.onTv)
    }

    @Test
    fun `a brightness drag while casting never touches the TV`() {
        state.begin(VideoAdjustment.BRIGHTNESS)

        state.adjust(VideoAdjustment.BRIGHTNESS, -(STAGE_HEIGHT / 4), STAGE_HEIGHT)

        assertTrue(sentToTv.isEmpty())
        assertFalse(state.feedback!!.onTv)
    }

    @Test
    fun `a drag is judged by where it began, so the TV going away mid-drag does not switch it to the phone`() {
        state.begin(VideoAdjustment.VOLUME)
        tv = null

        volumeDrag(-(STAGE_HEIGHT / 4))

        assertEquals(1, sentToTv.size)
        assertTrue(state.feedback!!.onTv)
    }

    @Test
    fun `ending the drag drops the readout and forgets the TV, so the next drag reads the TV afresh`() {
        state.begin(VideoAdjustment.VOLUME)
        volumeDrag(-(STAGE_HEIGHT / 4))
        state.end()
        assertEquals(null, state.feedback)

        tv = CastVolume(4, 20, false)
        state.begin(VideoAdjustment.VOLUME)

        assertNotNull(state.feedback)
        assertEquals(0.2f, state.feedback!!.fraction, TOLERANCE)
    }

    @Test
    fun `an unmeasured stage sends nothing to the TV`() {
        state.begin(VideoAdjustment.VOLUME)

        state.adjust(VideoAdjustment.VOLUME, -100f, stageHeight = 0f)

        assertTrue(sentToTv.isEmpty())
    }

    private companion object {
        const val STAGE_HEIGHT = 1000f
        const val TOLERANCE = 0.001f
    }
}
