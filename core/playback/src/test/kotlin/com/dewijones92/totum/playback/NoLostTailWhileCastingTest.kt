package com.dewijones92.totum.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Vitals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class NoLostTailWhileCastingTest {

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

    private fun dryPlayer(remote: Boolean, loading: Boolean = false): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "isLoading" -> loading
                "getBufferedPosition" -> DRY_BUFFERED_MS
                "getCurrentPosition" -> PLAYING_AT_MS
                "getDuration" -> DURATION_MS
                "getDeviceInfo" ->
                    DeviceInfo.Builder(if (remote) DeviceInfo.PLAYBACK_TYPE_REMOTE else DeviceInfo.PLAYBACK_TYPE_LOCAL)
                        .build()
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

    private fun stallFor(stalledMs: Long, player: Player) {
        val diagnostics = PlaybackDiagnostics(player = { player }, now = { clock })
        diagnostics.onPlaybackStateChanged(Player.STATE_BUFFERING)
        clock += stalledMs
        diagnostics.onPlaybackStateChanged(Player.STATE_IDLE)
    }

    @Test
    fun `control, the same dry buffer on the phone's own player says the tail is not coming`() {
        stallFor(20_000, dryPlayer(remote = false))

        assertTrue(Breadcrumbs.snapshot().any { "the tail is not coming" in it.message })
    }

    @Test
    fun `while casting the TV's buffer is not the phone's, so no lost tail is claimed`() {
        stallFor(20_000, dryPlayer(remote = true))

        assertFalse(Breadcrumbs.snapshot().any { "the tail is not coming" in it.message })
        assertEquals(null, Vitals.snapshot()["playback.stallsWithLoadingStopped"])
        assertEquals("1", Vitals.snapshot()["playback.stallsWhileCasting"])
    }

    @Test
    fun `a load that stops while casting is counted and not called a lost tail`() {
        val diagnostics = PlaybackDiagnostics(player = { dryPlayer(remote = true) }, now = { clock })

        diagnostics.onIsLoadingChanged(false)

        assertFalse(Breadcrumbs.snapshot().any { "the tail is not coming" in it.message })
        assertEquals(null, Vitals.snapshot()["playback.loadsStoppedShort"])
        assertEquals("1", Vitals.snapshot()["playback.loadPausesWhileCasting"])
    }

    private companion object {
        const val DRY_BUFFERED_MS = 3_693_514L
        const val PLAYING_AT_MS = 3_693_444L
        const val DURATION_MS = 3_728_366L
    }
}
