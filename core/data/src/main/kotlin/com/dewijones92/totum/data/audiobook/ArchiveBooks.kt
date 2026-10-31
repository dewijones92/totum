package com.dewijones92.totum.data.audiobook

import com.dewijones92.totum.common.HttpUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

public object ArchiveBooks {
    private const val METADATA = "https://archive.org/metadata/"
    private const val SEARCH = "https://archive.org/advancedsearch.php"
    private const val MAX_WORDS = 8
    private val WORD = Regex("""[\p{L}\p{N}]+""")

    public fun feedUrl(identifier: String): HttpUrl = HttpUrl.of(METADATA + encoded(identifier))

    public fun isBook(feedUrl: HttpUrl): Boolean = feedUrl.value.startsWith(METADATA)

    public fun artworkUrl(identifier: String): HttpUrl =
        HttpUrl.of("https://archive.org/services/img/${encoded(identifier)}")

    internal fun detailsUrl(identifier: String): String = "https://archive.org/details/${encoded(identifier)}"

    internal fun fileUrl(identifier: String, name: String): String =
        "https://archive.org/download/${encoded(identifier)}/" + name.split('/').joinToString("/") { encoded(it) }

    internal fun wordsOf(query: String): List<String> =
        WORD.findAll(query.lowercase()).map { it.value }.take(MAX_WORDS).toList()

    internal fun searchUrl(words: List<String>, rows: Int, loose: Boolean = false): HttpUrl {
        val clause = if (loose) {
            words.joinToString(" AND ") + " AND mediatype:audio AND " +
                "(collection:audio_bookspoetry OR collection:librivoxaudio OR subject:audiobook OR subject:audiobooks)"
        } else {
            words.joinToString(" AND ") { "(title:$it OR creator:$it)" } +
                " AND mediatype:audio AND collection:audio_bookspoetry"
        }
        val fields = listOf("identifier", "title", "creator", "downloads", "collection")
            .joinToString("") { "&fl[]=$it" }
        return HttpUrl.of(
            "$SEARCH?q=${encoded(clause)}${fields.replace("[]", "%5B%5D")}" +
                "&sort%5B%5D=${encoded("downloads desc")}&rows=$rows&output=json",
        )
    }

    private fun encoded(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
}

internal fun JsonObject.archiveText(key: String): String? = archiveTexts(key).firstOrNull()

internal fun JsonObject.archiveTexts(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> listOfNotNull(value.contentOrNull)
    else -> emptyList()
}.map { it.trim() }.filter { it.isNotEmpty() }
