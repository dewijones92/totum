package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.settings.AppPreferences

internal class AppPreferencesCaptionStore(private val preferences: AppPreferences) : CaptionStore {
    override fun choice(): CaptionChoice = preferences.settings.value.captions

    override fun save(choice: CaptionChoice) {
        Diag.log("subtitles", "dewidebug captions saved: ${if (choice.on) "on" else "off"} in ${choice.language}")
        preferences.setCaptions(choice)
    }
}
