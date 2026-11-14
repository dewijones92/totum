package com.dewijones92.totum.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ASilentTailOnAnUnnamedItemTest {

    private val tasks = mutableListOf<Runnable>()
    private val ticker = object : Ticker {
        override fun later(task: Runnable, delayMs: Long) {
            tasks += task
        }

        override fun cancel(task: Runnable) {
            tasks -= task
        }
    }
    private val seeks = mutableListOf<Long>()

    private fun player(): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "isPlaying" -> true
                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId("").build()
                "getDuration" -> 100_000L
                "getCurrentPosition" -> 98_000L
                "seekTo" -> {
                    seeks += args.last() as Long
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

    private fun tickOnce(named: ((Player) -> String?)? = null) {
        val p = player()
        val watch = if (named == null) {
            SilentTailWatch({ p }, { 6_000L }, ticker = ticker)
        } else {
            SilentTailWatch({ p }, { 6_000L }, null, named, ticker)
        }
        watch.enabled = true
        watch.start()
        tasks.toList().forEach { it.run() }
    }

    @Test
    fun `an item nothing names is not ended on its silent tail, and nothing fails`() {
        tickOnce()

        assertTrue("seeked to $seeks", seeks.isEmpty())
    }

    @Test
    fun `an item named by the id Totum sent is ended on its silent tail`() {
        tickOnce { "sent-id" }

        assertEquals(listOf(100_000L), seeks)
    }
}
