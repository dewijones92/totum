package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.common.Diag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class JellyfinReply(val code: Int, val body: String) {
    val ok: Boolean get() = code in OK_FROM..OK_TO

    private companion object {
        const val OK_FROM = 200
        const val OK_TO = 299
    }
}

class Jellyfin(
    private val http: OkHttpClient,
    private val base: String,
    private val key: String,
    private val libraryPaths: Map<Shelf, String>,
) {
    fun reachable(): Boolean = runCatching {
        http.newCall(
            Request.Builder().url(base.trimEnd('/') + "/System/Info/Public").build()
        ).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun created(shelf: Shelf, relative: String): Boolean {
        val path = "${libraryPaths.getValue(shelf).trimEnd('/')}/$relative"
        val body = JsonObject(
            mapOf(
                "Updates" to JsonArray(
                    listOf(JsonObject(mapOf("Path" to JsonPrimitive(path), "UpdateType" to JsonPrimitive("Created")))),
                ),
            ),
        ).toString()
        if (post("/Library/Media/Updated", body)) {
            Diag.log("filing", "told Jellyfin about $path")
            return true
        }
        val refreshed = post("/Library/Refresh", "")
        Diag.log(
            "filing",
            "Jellyfin's targeted update failed; full library refresh ${if (refreshed) "started" else "failed too"}"
        )
        return refreshed
    }

    fun open(path: String, range: String? = null): Response? = runCatching {
        val builder = Request.Builder().url(base.trimEnd('/') + path).header("Authorization", auth()).get()
        range?.let { builder.header("Range", it) }
        http.newCall(builder.build()).execute()
    }.onFailure { Diag.warn("jellyfin", "Jellyfin GET ${path.substringBefore('?')} could not be reached", it) }
        .getOrNull()

    fun get(path: String): String? = open(path)?.use { response ->
        if (response.isSuccessful) {
            response.body.string()
        } else {
            Diag.warn("jellyfin", "Jellyfin GET ${path.substringBefore('?')} answered ${response.code}")
            null
        }
    }

    fun streamUrl(id: String): String = "${base.trimEnd('/')}/Videos/$id/stream?static=true&api_key=$key"

    fun subtitleUrl(id: String, sourceId: String, index: Int): String =
        "${base.trimEnd('/')}/Videos/$id/$sourceId/Subtitles/$index/Stream.vtt?api_key=$key"

    private fun auth(): String = "MediaBrowser Token=\"$key\""

    fun call(path: String, json: String = "", tag: String = "jellyfin"): JellyfinReply? = runCatching {
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .header("Authorization", auth())
            .post(json.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response -> JellyfinReply(response.code, response.body.string()) }
    }.onFailure { Diag.warn(tag, "Jellyfin $path could not be reached", it) }.getOrNull()

    private fun post(path: String, json: String): Boolean {
        val reply = call(path, json, "filing") ?: return false
        if (!reply.ok) Diag.warn("filing", "Jellyfin $path answered ${reply.code}")
        return reply.ok
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
