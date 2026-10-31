package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.common.Diag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class Jellyfin(
    private val http: OkHttpClient,
    private val base: String,
    private val key: String,
    private val libraryPaths: Map<Shelf, String>,
) {
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

    private fun post(path: String, json: String): Boolean = runCatching {
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .header("Authorization", "MediaBrowser Token=\"$key\"")
            .post(json.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) Diag.warn("filing", "Jellyfin $path answered ${response.code}")
            response.isSuccessful
        }
    }.onFailure { Diag.warn("filing", "Jellyfin $path could not be reached", it) }.getOrDefault(false)

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
