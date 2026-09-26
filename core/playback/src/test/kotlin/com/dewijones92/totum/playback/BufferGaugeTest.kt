package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferGaugeTest {

    @Test
    fun `a healthy stream draining between loads never shows the gauge`() {
        val run = Playback(speed = 2f)
        run.play(seconds = 15, loadingKbpsOfMedia = 0.0, from = 50_000)
        repeat(4) {
            run.play(seconds = 5, loadingKbpsOfMedia = 8.0)
            run.play(seconds = 15, loadingKbpsOfMedia = 0.0)
        }

        assertEquals("ticks the gauge was on screen", 0, run.shownTicks)
    }

    @Test
    fun `a buffer running out is shown on every tick once it is shown, never blinking`() {
        val run = Playback(speed = 1f)
        run.play(seconds = 28, loadingKbpsOfMedia = 0.5, from = 20_000)

        assertTrue("it was never shown", run.shownTicks > 0)
        assertEquals("times it vanished and came back", 0, run.reappearances)
        assertEquals("ticks shown without saying it was falling", 0, run.shownNotFallingAfterFirst)
    }

    @Test
    fun `a moment with nothing buffered after a seek is not shown`() {
        val run = Playback(speed = 1f)
        run.play(seconds = 1, loadingKbpsOfMedia = 0.0, from = 0)
        run.jumpBufferTo(40_000)
        run.play(seconds = 20, loadingKbpsOfMedia = 0.0)

        assertEquals("ticks the gauge was on screen", 0, run.shownTicks)
    }

    @Test
    fun `a buffer hovering around the low mark does not blink the gauge`() {
        val run = Playback(speed = 1f)
        run.play(seconds = 4, loadingKbpsOfMedia = 0.0, from = 12_000)
        repeat(6) {
            run.play(seconds = 2, loadingKbpsOfMedia = 2.0)
            run.play(seconds = 2, loadingKbpsOfMedia = 0.0)
        }

        assertTrue("it was never shown", run.shownTicks > 0)
        assertEquals("times it vanished and came back", 0, run.reappearances)
    }

    @Test
    fun `the last seconds of an item loaded to its end are not a low buffer`() {
        val run = Playback(speed = 1f, durationMs = 300_000)
        run.seekTo(280_000, bufferedMs = 300_000)
        run.play(seconds = 20, loadingKbpsOfMedia = 0.0)

        assertEquals("ticks the gauge was on screen", 0, run.shownTicks)
    }

    @Test
    fun `the next item starts with nothing on screen`() {
        val run = Playback(speed = 1f)
        run.play(seconds = 20, loadingKbpsOfMedia = 0.5, from = 8_000)
        run.nextItem(bufferedMs = 5_000)
        run.play(seconds = 1, loadingKbpsOfMedia = 1.0)

        assertEquals("shown on the new item", 0, run.shownTicksThisItem)
    }

    @Test
    fun `a stall that freezes the player's state is shown once its wait is over`() {
        val gauge = BufferGauge()
        val stalled = state(positionMs = 60_000, bufferedMs = 60_300, playing = false)

        val atStart = gauge.update(stalled, nowMs = 1_000)
        val waitMs = gauge.waitMs(nowMs = 1_000)
        val afterTheWait = gauge.update(stalled, nowMs = 1_000 + (waitMs ?: 0))

        assertEquals(null, atStart)
        assertEquals(BufferAhead.SHOW_AFTER_MS, waitMs)
        assertEquals(0L, afterTheWait?.seconds)
        assertEquals(null, gauge.waitMs(nowMs = 1_000 + (waitMs ?: 0)))
    }

    @Test
    fun `nothing is waited for while the buffer is healthy`() {
        val gauge = BufferGauge()

        gauge.update(state(positionMs = 0, bufferedMs = 40_000, playing = true), nowMs = 0)

        assertEquals(null, gauge.waitMs(nowMs = 0))
    }

    private fun state(positionMs: Long, bufferedMs: Long, playing: Boolean) = PlaybackState(
        itemId = MediaItemId("item"),
        title = "Title",
        artist = null,
        artworkUrl = null,
        isPlaying = playing,
        positionMs = positionMs,
        durationMs = DURATION_MS,
        speed = 1f,
        bufferedPositionMs = bufferedMs,
    )

    private class Playback(private val speed: Float, private val durationMs: Long = DURATION_MS) {
        private val gauge = BufferGauge()
        private var item = MediaItemId("first")
        private var nowMs = 0L
        private var positionMs = 0L
        private var bufferedMs = 0L
        private var wasShown = false
        private var everShown = false
        private var sawFalling = false
        var shownTicks = 0
            private set
        var shownTicksThisItem = 0
            private set
        var reappearances = 0
            private set
        var shownNotFallingAfterFirst = 0
            private set

        fun play(seconds: Int, loadingKbpsOfMedia: Double, from: Long? = null) {
            from?.let { bufferedMs = positionMs + it }
            repeat(seconds * TICKS_PER_SECOND) {
                nowMs += TICK_MS
                positionMs = minOf(positionMs + (TICK_MS * speed).toLong(), bufferedMs)
                bufferedMs = minOf(bufferedMs + (TICK_MS * loadingKbpsOfMedia).toLong(), durationMs)
                tick()
            }
        }

        fun seekTo(positionMs: Long, bufferedMs: Long) {
            this.positionMs = positionMs
            this.bufferedMs = bufferedMs
        }

        fun jumpBufferTo(aheadMs: Long) {
            bufferedMs = positionMs + aheadMs
        }

        fun nextItem(bufferedMs: Long) {
            item = MediaItemId("second")
            positionMs = 0
            this.bufferedMs = bufferedMs
            wasShown = false
            shownTicksThisItem = 0
        }

        private fun tick() {
            val shown = gauge.update(
                PlaybackState(
                    itemId = item,
                    title = "Title",
                    artist = null,
                    artworkUrl = null,
                    isPlaying = true,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    bufferedPositionMs = bufferedMs,
                ),
                nowMs,
            )
            if (shown != null) {
                shownTicks++
                shownTicksThisItem++
                if (!wasShown && everShown) reappearances++
                if (sawFalling && !shown.falling) shownNotFallingAfterFirst++
                sawFalling = sawFalling || shown.falling
                everShown = true
            }
            wasShown = shown != null
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val TICKS_PER_SECOND = 4
        const val DURATION_MS = 3_600_000L
    }
}
