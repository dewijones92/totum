package com.dewijones92.totum.playback

internal class CaptionPreference(private var language: String = DEFAULT_CAPTION_LANGUAGE) {
    private var offFor: String? = null

    fun choose(itemId: String?, chosen: String?) {
        if (chosen == null) {
            offFor = itemId
        } else {
            language = chosen
            offFor = null
        }
    }

    fun languageFor(itemId: String?): String? = language.takeUnless { itemId != null && itemId == offFor }
}

internal const val DEFAULT_CAPTION_LANGUAGE = "en"
