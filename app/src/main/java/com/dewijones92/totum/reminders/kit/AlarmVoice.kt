package com.dewijones92.totum.reminders.kit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.RawRes
import com.dewijones92.totum.common.Diag
import java.util.Locale

sealed interface Utterance {
    data class Clip(@param:RawRes val resId: Int, val name: String) : Utterance

    data class Speech(val text: String) : Utterance
}

class AlarmVoice(private val context: Context, private val tag: String) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = ArrayDeque<Pair<Utterance, Float>>()
    private var player: MediaPlayer? = null
    private var speaking = false
    private var focus: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    val busy: Boolean get() = player != null || speaking

    fun say(utterance: Utterance, volumePercent: Int = FULL) {
        queue.addLast(utterance to volumePercent / PERCENT)
        Diag.log(tag, "dewidebug voice queued $utterance volume=$volumePercent% queue=${queue.size} busy=$busy")
        if (!busy) next()
    }

    fun stop() {
        queue.clear()
        player?.release()
        player = null
        tts?.stop()
        speaking = false
        release()
    }

    private fun next() {
        val (utterance, volume) = queue.removeFirstOrNull() ?: return release()
        if (focus == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
            Diag.log(tag, "dewidebug voice focus=${audio.requestAudioFocus(request)}")
            focus = request
        }
        when (utterance) {
            is Utterance.Clip -> playClip(utterance.resId, utterance.name, volume)
            is Utterance.Speech -> speak(utterance.text, volume)
        }
    }

    private fun playClip(@RawRes clip: Int, name: String, volume: Float) {
        val started = runCatching {
            val afd = context.resources.openRawResourceFd(clip)
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setVolume(volume, volume)
                setOnCompletionListener { done() }
                setOnErrorListener { _, what, extra ->
                    Diag.warn(tag, "dewidebug voice error what=$what extra=$extra")
                    done()
                    true
                }
                prepare()
                start()
            }
        }.onFailure { Diag.warn(tag, "dewidebug voice could not play clip $name", it) }.getOrNull()
        player = started
        if (started == null) next()
    }

    private fun speak(text: String, volume: Float) {
        speaking = true
        val engine = tts
        if (engine != null && ttsReady) return utter(engine, text, volume)
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            Diag.log(tag, "dewidebug voice text-to-speech ready=$ttsReady status=$status")
            val ready = tts
            if (ttsReady && ready != null) utter(ready, text, volume) else done()
        }
    }

    private fun utter(engine: TextToSpeech, text: String, volume: Float) {
        engine.setAudioAttributes(attributes)
        engine.language = Locale.UK
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = done()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Diag.warn(tag, "dewidebug voice text-to-speech failed for \"$text\"")
                done()
            }
        })
        val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "alarm-${System.nanoTime()}")
        Diag.log(tag, "dewidebug voice speaking \"$text\" result=$result")
        if (result != TextToSpeech.SUCCESS) done()
    }

    private fun done() {
        player?.release()
        player = null
        speaking = false
        next()
    }

    private fun release() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        const val PERCENT = 100f
        const val FULL = 100
    }
}
