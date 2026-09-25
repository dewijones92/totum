package com.dewijones92.totum.playback

import androidx.media3.common.Player
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Vitals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class AStallWithLoadingStoppedTest {

    private var clock = 0L

    @Before
    fun reset() {
        Vitals.clear()
        Breadcrumbs.clear()
    }

    @After
    fun tidy() {
        Vitals.clear()
        Breadcrumbs.clear()
    }

    private fun playerAt(positionMs: Long, bufferedMs: Long, durationMs: Long, loading: Boolean): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "isLoading" -> loading
                "getBufferedPosition" -> bufferedMs
                "getCurrentPosition" -> positionMs
                "getDuration" -> durationMs
                else -> defaultFor(method.returnType)
            }
        } as Player

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Long.TYPE -> 0L
        java.lang.Integer.TYPE -> 0
        java.lang.Float.TYPE -> 0f
        else -> null
    }

    private fun stallFor(stalledMs: Long, player: Player, recovers: Boolean = true) {
        val diagnostics = PlaybackDiagnostics(player = { player }, now = { clock })
        diagnostics.onPlaybackStateChanged(Player.STATE_BUFFERING)
        clock += stalledMs
        diagnostics.onPlaybackStateChanged(if (recovers) Player.STATE_READY else Player.STATE_IDLE)
    }

    private val dry = playerAt(positionMs = 3_693_444, bufferedMs = 3_693_514, durationMs = 3_728_366, loading = false)

    @Test
    fun `a stall that lasts with loading stopped and the buffer dry says the tail is not coming`() {
        stallFor(20_000, dry, recovers = false)

        assertEquals("1", Vitals.snapshot()["playback.stallsWithLoadingStopped"])
        assertTrue(Breadcrumbs.snapshot().any { "the tail is not coming" in it.message })
    }

    @Test
    fun `a seek's masked moment of buffering that recovers at once is not that`() {
        stallFor(40, playerAt(positionMs = 67_714, bufferedMs = 67_714, durationMs = 846_201, loading = false))

        assertEquals(null, Vitals.snapshot()["playback.stallsWithLoadingStopped"])
        assertEquals(null, Vitals.snapshot()["playback.stallsNotWaitingOnTheNetwork"])
    }

    @Test
    fun `a dry buffer that is still loading is an ordinary stall`() {
        stallFor(
            20_000,
            playerAt(positionMs = 3_693_444, bufferedMs = 3_693_514, durationMs = 3_728_366, loading = true)
        )

        assertEquals(null, Vitals.snapshot()["playback.stallsWithLoadingStopped"])
    }

    @Test
    fun `a lasting stall with seconds buffered and nothing loading is named as not the network`() {
        stallFor(15_000, playerAt(positionMs = 600_000, bufferedMs = 612_000, durationMs = 3_600_000, loading = false))

        assertEquals("1", Vitals.snapshot()["playback.stallsNotWaitingOnTheNetwork"])
        assertTrue(Breadcrumbs.snapshot().any { "not waiting on the network" in it.message })
    }
}
