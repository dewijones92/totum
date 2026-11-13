package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

public class Cinemeta(
    private val client: OkHttpClient,
    private val base: String = CINEMETA_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    internal data class Meta(val id: String, val name: String, val released: String)

    internal suspend fun find(kind: String, title: String, year: String?): Meta? {
        if (title.wordsOf().isEmpty()) return null
        val encoded = URLEncoder.encode(title, Charsets.UTF_8).replace("+", "%20")
        val body = get("$base/catalog/$kind/top/search=$encoded.json") ?: return null
        val metas = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)
            ?.get("metas") as? JsonArray ?: return null
        val words = title.wordsOf()
        val candidates = metas.mapNotNull { element ->
            val meta = element as? JsonObject ?: return@mapNotNull null
            val id = (meta["imdb_id"] ?: meta["id"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = meta["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val released = meta["releaseInfo"]?.jsonPrimitive?.contentOrNull.orEmpty()
            Meta(id, name, released).takeIf { id.startsWith("tt") }
        }
        val named = candidates.filter { it.name.wordsOf().containsAll(words) }
        val match = named.firstOrNull { year != null && it.released.startsWith(year) }
            ?: year?.let { y -> candidates.firstOrNull { it.name.wordsOf().containsAll(words + y) } }
            ?: named.firstOrNull()
        Diag.log(
            "torrent",
            "cinemeta $kind catalogue \"$title\": ${candidates.size} entr(ies), " +
                (match?.let { "using \"${it.name}\" (${it.id})" } ?: "none named with every word of the search"),
        )
        return match
    }

    public suspend fun posterFor(releaseName: String): String? {
        val release = ReleaseName(releaseName)
        val words = release.title
        if (words.isEmpty()) return null
        val marker = release.tail.firstOrNull().orEmpty()
        val year = marker.takeIf { YEAR.matches(it) }
        val kind = if (EPISODE.matches(marker)) SERIES else MOVIE
        val shorter = generateSequence(words) { it.dropLast(1).takeIf { rest -> rest.size >= MIN_WORDS } }
        return try {
            var found: Meta? = null
            for (candidate in shorter) {
                found = find(kind, candidate.joinToString(" "), year)
                if (found != null) break
            }
            found?.let { Posters.forImdb(it.id.substringBefore(':')) }
        } catch (e: IOException) {
            Diag.warn("torrent", "cinemeta could not be reached for \"$releaseName\"", e)
            null
        }
    }

    internal suspend fun get(url: String): String? {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).await().use { response ->
            if (response.isSuccessful) {
                response.body.string()
            } else {
                Diag.warn("torrent", "${url.substringAfter("//").substringBefore('?')} HTTP ${response.code}")
                null
            }
        }
    }

    private fun String.wordsOf(): Set<String> =
        lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()

    public companion object {
        public const val CINEMETA_BASE: String = "https://v3-cinemeta.strem.io"
        private const val MOVIE = "movie"
        private const val SERIES = "series"
        private const val MIN_WORDS = 2
        private val YEAR = Regex("""(19|20)\d\d""")
        private val EPISODE = Regex("""(?i)s\d{1,2}(e\d{1,3})?""")
    }
}

internal object Posters {
    private const val FOR_IMDB = "https://images.metahub.space/poster/medium"

    fun forImdb(id: String): String = "$FOR_IMDB/$id/img"
}
