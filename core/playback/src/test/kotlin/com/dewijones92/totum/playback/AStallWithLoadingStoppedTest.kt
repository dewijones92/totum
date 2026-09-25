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

    private fun stallWith(player: Player) {
        PlaybackDiagnostics(player = { player }, now = { 0L }).onPlaybackStateChanged(Player.STATE_BUFFERING)
    }

    @Test
    fun `a stall with loading stopped and the buffer dry says the tail is not coming`() {
        stallWith(playerAt(positionMs = 3_693_444, bufferedMs = 3_693_514, durationMs = 3_728_366, loading = false))

        assertEquals("1", Vitals.snapshot()["playback.stallsWithLoadingStopped"])
        assertTrue(Breadcrumbs.snapshot().any { "the tail is not coming" in it.message })
    }

    @Test
    fun `a seek's moment of buffering with minutes buffered is not that`() {
        stallWith(playerAt(positionMs = 77_700, bufferedMs = 337_361, durationMs = 846_201, loading = false))

        assertEquals(null, Vitals.snapshot()["playback.stallsWithLoadingStopped"])
    }

    @Test
    fun `a dry buffer that is still loading is an ordinary stall`() {
        stallWith(playerAt(positionMs = 3_693_444, bufferedMs = 3_693_514, durationMs = 3_728_366, loading = true))

        assertEquals(null, Vitals.snapshot()["playback.stallsWithLoadingStopped"])
    }
}
