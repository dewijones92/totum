package com.dewijones92.totum.common

import java.net.URLDecoder
import java.util.Locale

/**
 * What a stream's SOUND is: which language it carries, and whether that is the uploader's own
 * track or a dub laid over it.
 *
 * One type for every picker, because "which of these streams should I play?" was answered in
 * five places with rules that disagreed, and the one place that ignored language is the one
 * that chose what actually played. See `docs/features/audio-tracks.md`.
 */
public data class AudioTrackTag(
    /** BCP-47 as the source states it (`en`, `en-US`, `de-DE`); null when nothing says. */
    val languageCode: String? = null,
    /** The uploader's own track. */
    val original: Boolean = false,
    /** A dub laid over the original — YouTube's automatic ones included. */
    val dubbed: Boolean = false,
) {
    /** True when this track is in [language], comparing primary subtags (`en-US` speaks `en`). */
    public fun speaks(language: String): Boolean {
        val mine = languageCode ?: return false
        return mine.primarySubtag().equals(language.primarySubtag(), ignoreCase = true)
    }

    /**
     * How to name this track in a menu: "English (original)", "German (dubbed)".
     *
     * The suffix is the point. Two rows both saying "English" — one the real track and one a
     * machine dub — is a menu that cannot be used.
     */
    public val label: String
        get() {
            val name = languageCode
                ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
                ?.ifBlank { null }
                ?: languageCode
                ?: UNKNOWN_LABEL
            val note = when {
                original -> ORIGINAL_NOTE
                dubbed -> DUBBED_NOTE
                else -> null
            }
            return if (note == null) name else "$name ($note)"
        }

    public companion object {
        /** Nothing known — which must never lose to a track known to be the wrong language. */
        public val Unknown: AudioTrackTag = AudioTrackTag()

        /**
         * YouTube's `xtags`, e.g. `acont=dubbed-auto:lang=de-DE`.
         *
         * Colon-separated `key=value`, which is not a URL query and not JSON, so it gets its
         * own parse rather than being forced through one.
         */
        public fun fromXtags(xtags: String?): AudioTrackTag {
            val pairs = xtags?.split(XTAG_SEPARATOR).orEmpty()
                .mapNotNull { pair ->
                    val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
                    val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
                    if (name.isEmpty() || value.isEmpty()) null else name.lowercase() to value
                }
                .toMap()
            if (pairs.isEmpty()) return Unknown
            val content = pairs[KEY_CONTENT]?.lowercase()
            return AudioTrackTag(
                languageCode = pairs[KEY_LANGUAGE],
                original = content == CONTENT_ORIGINAL,
                dubbed = content != null && content.startsWith(CONTENT_DUBBED),
            )
        }

        /**
         * The tag a googlevideo URL declares about itself, or [Unknown].
         *
         * Needed because the extractor does not always label an HLS variant, and the URL always
         * does. Report 0.1.373 played `…/sgoap/clen=…;xtags=acont=dubbed-auto:lang=de-DE/…` —
         * an automatic German dub of an English talk, chosen because every picker looked at
         * height and none looked at this.
         */
        public fun inUrl(url: String?): AudioTrackTag {
            val decoded = url?.let { decode(it) } ?: return Unknown
            val marker = decoded.indexOf(XTAGS_MARKER).takeIf { it >= 0 } ?: return Unknown
            val rest = decoded.substring(marker + XTAGS_MARKER.length)
            return fromXtags(rest.takeWhile { it !in XTAGS_TERMINATORS })
        }

        private fun decode(url: String): String =
            runCatching { URLDecoder.decode(url, Charsets.UTF_8.name()) }.getOrDefault(url)

        private fun String.primarySubtag(): String = substringBefore('-')

        private const val XTAG_SEPARATOR = ':'
        private const val XTAGS_MARKER = "xtags="
        private const val XTAGS_TERMINATORS = ";&/? "
        private const val KEY_CONTENT = "acont"
        private const val KEY_LANGUAGE = "lang"
        private const val CONTENT_ORIGINAL = "original"
        private const val CONTENT_DUBBED = "dubbed"
        private const val ORIGINAL_NOTE = "original"
        private const val DUBBED_NOTE = "dubbed"
        private const val UNKNOWN_LABEL = "Unknown"
    }
}

/**
 * Best-first order for audio tracks: a language you asked for, then the uploader's own track.
 *
 * [wanted] is an explicit choice when the user has made one, and the device's language
 * otherwise. An unknown language deliberately outranks a known-unwanted one — most videos
 * label nothing, and treating silence as "wrong" would reject the only track there is.
 */
public fun audioLanguagePreference(wanted: List<String>): Comparator<AudioTrackTag> =
    compareBy<AudioTrackTag> { tag -> tag.languageRank(wanted) }.thenBy { tag -> tag.originRank }

private fun AudioTrackTag.languageRank(wanted: List<String>): Int = when {
    wanted.any(::speaks) -> LANGUAGE_WANTED
    languageCode == null -> LANGUAGE_UNSTATED
    else -> LANGUAGE_UNWANTED
}

private val AudioTrackTag.originRank: Int
    get() = when {
        original -> ORIGIN_ORIGINAL
        dubbed -> ORIGIN_DUBBED
        else -> ORIGIN_UNSTATED
    }

private const val LANGUAGE_WANTED = 2
private const val LANGUAGE_UNSTATED = 1
private const val LANGUAGE_UNWANTED = 0
private const val ORIGIN_ORIGINAL = 2
private const val ORIGIN_UNSTATED = 1
private const val ORIGIN_DUBBED = 0
