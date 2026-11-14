package com.dewijones92.totum.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AResumeWithNoItemNameTest {

    private var clock = 0L
    private val asked = mutableListOf<Triple<String, Long, Long?>>()
    private val rewind = ResumeRewind({ id, at, paused -> asked += Triple(id, at, paused) }, { clock })
    private val talk = ResumeFacts(talk = true, live = false, enabled = true)

    private class Moving(var playWhenReady: Boolean, var positionMs: Long)

    private fun receiver(itemId: String, state: Moving): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(itemId).build()
                "getCurrentPosition" -> state.positionMs
                "getPlayWhenReady" -> state.playWhenReady
                "getPlaybackState" -> Player.STATE_READY
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "isCurrentMediaItemLive" -> false
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player

    private fun listener(player: Player, named: ((Player) -> String?)? = null) =
        if (named == null) {
            ResumeRewindListener({ player }, rewind) { talk }
        } else {
            ResumeRewindListener({ player }, rewind, { _, _ -> }, named) { talk }
        }

    private fun pauseThenPlayAfter(listener: ResumeRewindListener, state: Moving, pausedMs: Long) {
        state.playWhenReady = false
        listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        clock += pausedMs
        state.playWhenReady = true
        listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
    }

    @Test
    fun `a receiver that names no item never makes the rewind ask about a blank id`() {
        val state = Moving(playWhenReady = true, positionMs = 600_000)
        pauseThenPlayAfter(listener(receiver("", state)), state, pausedMs = 30_000)
        assertTrue("asked about ${asked.map { "'${it.first}'" }}", asked.none { it.first.isBlank() })
    }

    @Test
    fun `a blank id on the resume moment is refused whatever the listener passes`() {
        rewind.onChange(PlayerMoment("", 600_000, playWhenReady = false, ended = false, focusLost = false), false, talk)
        clock += 30_000
        val target = rewind.onChange(PlayerMoment("", 600_000, true, ended = false, focusLost = false), false, talk)
        assertEquals(null, target)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a receiver that names nothing is rewound and asked about under the id Totum sent`() {
        val state = Moving(playWhenReady = true, positionMs = 600_000)
        val standIn = "the-id-totum-sent"
        pauseThenPlayAfter(listener(receiver("", state)) { standIn }, state, pausedMs = 30_000)
        assertEquals(listOf(Triple(standIn, 600_000L, 30_000L)), asked)
    }

    @Test
    fun `a receiver that names nothing and no stand-in is left alone, not a crash`() {
        val state = Moving(playWhenReady = true, positionMs = 600_000)
        pauseThenPlayAfter(listener(receiver("", state)) { null }, state, pausedMs = 30_000)
        assertTrue(asked.isEmpty())
    }
}
