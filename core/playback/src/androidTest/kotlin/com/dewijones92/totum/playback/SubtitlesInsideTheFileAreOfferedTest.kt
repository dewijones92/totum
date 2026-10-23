package com.dewijones92.totum.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SubtitlesInsideTheFileAreOfferedTest {

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

    @Test
    fun anMkvsOwnSubtitleTrackIsOfferedAndShowsItsCuesWhenChosen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mkv = instrumentation.context.assets.open("subtitled.mkv").use { it.readBytes() }
        LoopbackHttp { LoopbackHttp.Reply("200 OK", mkv) }.use { media ->
            val tracks = AtomicReference<Tracks?>(null)
            val cue = AtomicReference<String?>(null)
            val player = onMain {
                val context = instrumentation.targetContext
                ExoPlayer.Builder(context).setMediaSourceFactory(playbackSources(context)).build().apply {
                    addListener(
                        object : Player.Listener {
                            override fun onTracksChanged(changed: Tracks) {
                                if (changed.containsType(C.TRACK_TYPE_TEXT)) tracks.set(changed)
                            }

                            override fun onCues(cueGroup: CueGroup) {
                                cueGroup.cues.firstOrNull()?.text?.let { cue.compareAndSet(null, it.toString()) }
                            }
                        },
                    )
                    setMediaItem(MediaItem.fromUri(media.url))
                    prepare()
                }
            }
            val until = System.currentTimeMillis() + WATCH_MS
            while (tracks.get() == null && System.currentTimeMillis() < until) Thread.sleep(100)
            val choices = subtitleChoices(emptyList(), tracks.get()?.textTracksSeen().orEmpty())
            android.util.Log.i("dewidebug", "mkv subtitle choices: $choices")
            assertEquals(listOf(SubtitleChoice("en", "English (in the file)")), choices)

            onMain {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(choices.single().languageCode)
                    .build()
                player.play()
            }
            while (cue.get() == null && System.currentTimeMillis() < until) Thread.sleep(100)
            onMain { player.release() }
            assertTrue("cue=${cue.get()}", cue.get()?.contains("inside the file") == true)
        }
    }

    private companion object {
        const val WATCH_MS = 15_000L
    }
}
