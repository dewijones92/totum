package com.dewijones92.totum.playback

public const val DEFAULT_CAPTION_LANGUAGE: String = "en"

public data class CaptionChoice(val on: Boolean = true, val language: String = DEFAULT_CAPTION_LANGUAGE) {
    val languageOrNull: String? get() = language.takeIf { on }
}
