package com.dewijones92.totum.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AnOriginalLanguageTrackShowsCuesTest {

    private val main = Handler(Looper.getMainLooper())

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        main.post {
            result.set(block())
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return result.get()
    }

    private fun cuesShownFor(language: String): String? {
        com.dewijones92.totum.common.Diag.sink = com.dewijones92.totum.common.Diag.Sink { _, tag, message, _ ->
            android.util.Log.i("dewidebug", "[$tag] $message")
        }
        val wav = wav()
        val ttml = TTML.toByteArray()
        LoopbackHttp { LoopbackHttp.Reply("200 OK", wav) }.use { media ->
            LoopbackHttp { LoopbackHttp.Reply("200 OK", ttml) }.use { captions ->
                val subtitleUrl = captions.url.substringBeforeLast("/videoplayback") +
                    "/api/timedtext?v=x&lang=en&fmt=ttml"
                val seen = AtomicReference<String?>(null)
                val player = onMain {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    ExoPlayer.Builder(context).setMediaSourceFactory(playbackSources(context)).build().apply {
                        addAnalyticsListener(SubtitleTrail())
                        addListener(
                            object : Player.Listener {
                                override fun onCues(cueGroup: CueGroup) {
                                    cueGroup.cues.firstOrNull()?.text?.let { seen.compareAndSet(null, it.toString()) }
                                }
                            },
                        )
                        val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                            .setMimeType(MimeTypes.APPLICATION_TTML)
                            .setLanguage(language)
                            .build()
                        setMediaItem(
                            MediaItem.Builder().setUri(media.url).setSubtitleConfigurations(listOf(subtitle)).build()
                        )
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setPreferredTextLanguage(language)
                            .build()
                        prepare()
                        play()
                    }
                }
                val until = System.currentTimeMillis() + WATCH_MS
                while (seen.get() == null && System.currentTimeMillis() < until) Thread.sleep(100)
                android.util.Log.i("dewidebug", "original-language cues: language=$language saw=${seen.get()}")
                onMain { player.release() }
                return seen.get()
            }
        }
    }

    @Test
    fun anEnglishTrackShowsItsCues() {
        assertTrue(cuesShownFor("en") != null)
    }

    @Test
    fun anOriginalLanguageTrackShowsItsCuesToo() {
        assertTrue(cuesShownFor("en-orig") != null)
    }

    private fun wav(): ByteArray {
        val data = SAMPLE_RATE * 2 * SECONDS
        return ByteBuffer.allocate(WAV_HEADER + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(WAV_HEADER - 8 + data).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(data)
        }.array()
    }

    private companion object {
        const val TTML = """<?xml version="1.0" encoding="utf-8"?><tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
            """<p begin="0.000s" end="20.000s">A caption</p></div></body></tt>"""
        const val SAMPLE_RATE = 8_000
        const val SECONDS = 30
        const val WAV_HEADER = 44
        const val WATCH_MS = 10_000L
    }
}
