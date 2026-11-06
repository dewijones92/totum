package com.dewijones92.totum.recorder

import android.content.Context
import androidx.core.content.edit

data class RecorderChoices(val mic: Boolean = true, val face: Boolean = true, val taps: Boolean = true)

class RecorderChoiceStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): RecorderChoices = RecorderChoices(
        mic = prefs.getBoolean(MIC, true),
        face = prefs.getBoolean(FACE, true),
        taps = prefs.getBoolean(TAPS, true),
    )

    fun write(choices: RecorderChoices) = prefs.edit {
        putBoolean(MIC, choices.mic)
        putBoolean(FACE, choices.face)
        putBoolean(TAPS, choices.taps)
    }

    private companion object {
        const val FILE = "recorder-choices"
        const val MIC = "mic"
        const val FACE = "face"
        const val TAPS = "taps"
    }
}
