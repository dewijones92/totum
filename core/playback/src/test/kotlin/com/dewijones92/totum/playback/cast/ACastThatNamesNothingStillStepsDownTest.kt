package com.dewijones92.totum.playback.cast

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.playback.Ticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ACastThatNamesNothingStillStepsDownTest {

    private var clock = 1_000_000L
    private val tasks = mutableListOf<Runnable>()
    private val ticker = object : Ticker {
        override fun later(task: Runnable, delayMs: Long) {
            tasks += task
        }

        override fun cancel(task: Runnable) {
            tasks -= task
        }
    }
    private val asked = mutableListOf<String>()
    private val resent = mutableListOf<List<String>>()
    private val sent = MediaItem.Builder().setMediaId("sent-id").build()
    private val blank = MediaItem.Builder().setMediaId("").build()

    private fun receiver(): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItem", "getMediaItemAt" -> blank
                "getMediaItemCount" -> 1
                "getCurrentMediaItemIndex" -> 0
                "getPlaybackState" -> Player.STATE_BUFFERING
                "getPlayWhenReady" -> true
                "getCurrentPosition" -> 30_000L
                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    resent += (args[0] as List<MediaItem>).map { it.mediaId }
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player

    private fun watch(sentItem: MediaItem?): CastStepDownWatch {
        val judge = CastStallJudge(StallsBeforeStepDown({ clock }), { clock })
        return CastStepDownWatch(
            receiver(),
            stepDownFor = { id ->
                asked += id
                if (id.isBlank()) null else CastStep(QualityLabel(1080, null), 480)
            },
            sentItem = { sentItem },
            judge = judge,
            ticker = ticker,
        )
    }

    private fun freezeForTwelveSeconds(watch: CastStepDownWatch) {
        watch.onMediaItemTransition(blank, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        clock += 5_000
        watch.onPlaybackStateChanged(Player.STATE_READY)
        clock += 30_000
        watch.onPlaybackStateChanged(Player.STATE_BUFFERING)
        clock += 12_000
        tasks.toList().forEach { it.run() }
    }

    @Test
    fun `a frozen cast whose receiver names nothing steps down under the id Totum sent`() {
        freezeForTwelveSeconds(watch(sent))

        assertEquals(listOf("sent-id"), asked)
        assertEquals(listOf(listOf("sent-id")), resent)
    }

    @Test
    fun `a frozen cast with nothing to name it asks for no step-down and does not fail`() {
        freezeForTwelveSeconds(watch(null))

        assertTrue("asked for $asked", asked.isEmpty())
        assertTrue(resent.isEmpty())
    }
}
