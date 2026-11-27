package com.dewijones92.totum.playback.cast

import java.util.Locale

public object CaptionPick {

    public fun indexOf(offered: List<String>, wanted: List<String>): Int? =
        wanted.firstNotNullOfOrNull { language -> offered.indexOf(language).takeIf { it >= 0 } }
            ?: wanted.firstNotNullOfOrNull { language ->
                offered.indexOfFirst { it.substringBefore('-') == language.substringBefore('-') }.takeIf { it >= 0 }
            }

    public fun normal(code: String): String {
        val locale = Locale.forLanguageTag(code.trim().replace('_', '-'))
        val language = locale.language.lowercase()
        if (language.isEmpty()) return ""
        val short = twoLetter[language] ?: language
        val region = locale.country.lowercase()
        return if (region.isEmpty()) short else "$short-$region"
    }

    private val twoLetter: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) }
    }
}
