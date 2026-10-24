package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import kotlin.time.TimeSource

public class Torrentio(
    private val client: OkHttpClient,
    private val cinemetaBase: String = CINEMETA_BASE,
    private val torrentioBase: String = TORRENTIO_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun search(query: String): TorrentSearchResult {
        val started = TimeSource.Monotonic.markNow()
        val asked = Asked.of(query)
        return try {
            val results = coroutineScope {
                listOf(MOVIE, SERIES).map { kind -> async { findAndList(kind, asked) } }.awaitAll().flatten()
            }
            Diag.log(
                "torrent",
                "torrentio \"$query\" -> ${results.size} stream(s) in ${started.elapsedNow().inWholeMilliseconds}ms " +
                    "[title=\"${asked.title}\" episode=${asked.season}:${asked.episode}]",
            )
            TorrentSearchResult.Success(results)
        } catch (e: IOException) {
            Diag.warn("torrent", "torrentio \"$query\" could not be reached", e)
            TorrentSearchResult.Failure(e.message ?: "Torrentio could not be reached")
        }
    }

    private suspend fun findAndList(kind: String, asked: Asked): List<TorrentResult> {
        val meta = catalogue(kind, asked.title) ?: return emptyList()
        val path = if (kind == SERIES) "$kind/${meta.id}:${asked.season}:${asked.episode}" else "$kind/${meta.id}"
        val body = get("$torrentioBase/stream/$path.json") ?: return emptyList()
        val streams = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)
            ?.get("streams") as? JsonArray ?: return emptyList()
        val results = streams.mapNotNull { (it as? JsonObject)?.toResult() }
        Diag.log(
            "torrent",
            "torrentio $kind \"${meta.name}\" (${meta.id}) -> ${results.size} of ${streams.size} stream(s) " +
                "had an info hash",
        )
        return results
    }

    private suspend fun catalogue(kind: String, title: String): Meta? {
        val encoded = URLEncoder.encode(title, Charsets.UTF_8).replace("+", "%20")
        val body = get("$cinemetaBase/catalog/$kind/top/search=$encoded.json") ?: return null
        val metas = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)
            ?.get("metas") as? JsonArray ?: return null
        val words = title.wordsOf()
        val candidates = metas.mapNotNull { element ->
            val meta = element as? JsonObject ?: return@mapNotNull null
            val id = (meta["imdb_id"] ?: meta["id"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = meta["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Meta(id, name).takeIf { id.startsWith("tt") }
        }
        val match = candidates.firstOrNull { it.name.wordsOf().containsAll(words) }
        Diag.log(
            "torrent",
            "torrentio $kind catalogue \"$title\": ${candidates.size} entr(ies), " +
                (match?.let { "using \"${it.name}\" (${it.id})" } ?: "none named with every word of the search"),
        )
        return match
    }

    private suspend fun get(url: String): String? {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).await().use { response ->
            if (response.isSuccessful) {
                response.body.string()
            } else {
                Diag.warn("torrent", "torrentio ${url.substringAfter("//").substringBefore('?')} HTTP ${response.code}")
                null
            }
        }
    }

    private fun JsonObject.toResult(): TorrentResult? {
        val hash = this["infoHash"]?.jsonPrimitive?.contentOrNull?.takeIf { HASH.matches(it) } ?: return null
        val lines = this["title"]?.jsonPrimitive?.contentOrNull?.lines().orEmpty()
        val name = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val facts = lines.lastOrNull().orEmpty()
        val site = SITE.find(facts)?.groupValues?.get(1)
        return TorrentResult(
            title = name,
            magnet = magnetFor(hash, name),
            seeders = SEEDERS.find(facts)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            sizeBytes = SIZE.find(facts)?.let { sizeOf(it.groupValues[1], it.groupValues[2]) } ?: 0,
            indexer = site?.let { "$NAME · $it" } ?: NAME,
        )
    }

    private data class Meta(val id: String, val name: String)

    private data class Asked(val title: String, val season: Int, val episode: Int) {
        companion object {
            fun of(query: String): Asked {
                val episode = EPISODE.find(query)
                val title = (episode?.let { query.removeRange(it.range) } ?: query).trim().replace(SPACES, " ")
                return Asked(
                    title = title,
                    season = episode?.groupValues?.get(1)?.toInt() ?: 1,
                    episode = episode?.groupValues?.get(2)?.toInt() ?: 1,
                )
            }
        }
    }

    public companion object {
        public const val NAME: String = "Torrentio"
        public const val CINEMETA_BASE: String = "https://v3-cinemeta.strem.io"
        public const val TORRENTIO_BASE: String = "https://torrentio.strem.fun"
        private const val MOVIE = "movie"
        private const val SERIES = "series"
        private val EPISODE = Regex("""\b[sS](\d{1,2})\s*[eE](\d{1,3})\b""")
        private val SPACES = Regex("""\s+""")
        private val SEEDERS = Regex("""👤\s*(\d+)""")
        private val SIZE = Regex("""💾\s*([\d.]+)\s*(TB|GB|MB|KB)""")
        private val SITE = Regex("""⚙️\s*(\S+)""")
        private val UNITS = mapOf("KB" to 1_000.0, "MB" to 1_000_000.0, "GB" to 1_000_000_000.0, "TB" to 1e12)

        private fun sizeOf(amount: String, unit: String): Long? =
            amount.toDoubleOrNull()?.let { Math.round(it * UNITS.getValue(unit)) }

        private fun String.wordsOf(): Set<String> =
            lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()
    }
}
