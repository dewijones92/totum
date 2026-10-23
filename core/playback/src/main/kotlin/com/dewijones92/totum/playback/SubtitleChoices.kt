package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.dewijones92.totum.common.SubtitleTrack
import java.util.Locale

public data class SubtitleChoice(val languageCode: String, val label: String)

internal data class TextTrackSeen(val language: String?, val label: String?)

internal const val UNDETERMINED_LANGUAGE = "und"

internal fun subtitleChoices(fetched: List<SubtitleTrack>, inPlayer: List<TextTrackSeen>): List<SubtitleChoice> {
    val offered = fetched.map { SubtitleChoice(it.languageCode, it.label) }
    val fetchedLanguages = fetched.mapTo(mutableSetOf()) { it.baseLanguage }
    val inFile = inPlayer
        .map { track ->
            val code = track.language?.takeUnless { it.isBlank() || it == UNDETERMINED_LANGUAGE }
                ?: UNDETERMINED_LANGUAGE
            SubtitleChoice(code, track.label?.takeIf { it.isNotBlank() } ?: inTheFile(code))
        }
        .filter { it.languageCode.substringBefore('-').lowercase() !in fetchedLanguages }
        .distinctBy { it.languageCode }
    return offered + inFile
}

internal fun Tracks.textTracksSeen(): List<TextTrackSeen> =
    groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
        (0 until group.length).map { group.getTrackFormat(it) }.map { TextTrackSeen(it.language, it.label) }
    }

private fun inTheFile(code: String): String {
    val name = if (code == UNDETERMINED_LANGUAGE) {
        "Subtitles"
    } else {
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
    }
    return "$name (in the file)"
}
