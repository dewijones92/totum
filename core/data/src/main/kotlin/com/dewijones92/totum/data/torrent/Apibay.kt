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
import java.time.Instant
import kotlin.time.TimeSource

public class Apibay(
    private val client: OkHttpClient,
    private val base: String = BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun search(query: String): TorrentSearchResult {
        val started = TimeSource.Monotonic.markNow()
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8).replace("+", "%20")
        val body = try {
            get("$base/q.php?q=$encoded")
        } catch (e: IOException) {
            Diag.warn("torrent", "apibay \"$query\" could not be reached", e)
            return TorrentSearchResult.Failure("The Pirate Bay's API could not be reached")
        } ?: return TorrentSearchResult.Failure("The Pirate Bay's API did not answer")
        val rows = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: return TorrentSearchResult.Failure("The Pirate Bay's API answered something unreadable")
        val results = rows.mapNotNull { (it as? JsonObject)?.toResult() }
        val standings = results.groupingBy { it.uploader }.eachCount().entries.joinToString { "${it.key}=${it.value}" }
        Diag.log(
            "torrent",
            "apibay \"$query\" -> ${results.size} result(s) in ${started.elapsedNow().inWholeMilliseconds}ms " +
                "[uploaders: ${standings.ifEmpty { "none" }}]",
        )
        return TorrentSearchResult.Success(results)
    }

    private suspend fun get(url: String): String? {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).await().use { response ->
            if (response.isSuccessful) {
                response.body.string()
            } else {
                Diag.warn("torrent", "apibay HTTP ${response.code}")
                null
            }
        }
    }

    private fun JsonObject.toResult(): TorrentResult? {
        fun text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        val hash = text("info_hash")?.takeIf { HASH.matches(it) && !NO_HASH.matches(it) } ?: return null
        val name = text("name")?.takeIf { it.isNotBlank() } ?: return null
        return TorrentResult(
            title = name,
            link = TorrentLink.Magnet(magnetFor(hash, name)),
            seeders = text("seeders")?.toIntOrNull() ?: 0,
            sizeBytes = text("size")?.toLongOrNull() ?: 0,
            indexer = INDEXER,
            leechers = text("leechers")?.toIntOrNull(),
            publishedAt = text("added")?.toLongOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            fileCount = text("num_files")?.toIntOrNull(),
            uploader = standingOf(text("status")),
            uploaderName = text("username")?.takeIf { it.isNotBlank() },
        )
    }

    public companion object {
        public const val NAME: String = "The Pirate Bay's API"
        public const val BASE: String = "https://apibay.org"
        public const val INDEXER: String = "The Pirate Bay"
        private val NO_HASH = Regex("0{40}")

        internal fun standingOf(status: String?): Uploader = when (status?.lowercase()) {
            "vip" -> Uploader.VIP
            "trusted", "helper", "moderator", "supermod", "admin" -> Uploader.TRUSTED
            "member", "user" -> Uploader.MEMBER
            else -> Uploader.UNKNOWN
        }
    }
}
