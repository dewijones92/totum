package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A YouTube `visitorData` for anonymous WEB-family player calls, or null when none can be had. */
public fun interface VisitorIdSource {
    public suspend fun current(): String?
}

/**
 * Asks `/youtubei/v1/visitor_id` once as the WEB client and keeps the answer for the process — the
 * embedded player wants a visitor id in its context, and SmartTube fetches exactly this before its first
 * player call (captured 2026-09-06). One per process: a visitor is a session, not a per-video fact.
 */
public class HttpVisitorIdSource(
    private val client: OkHttpClient,
    private val url: String = VISITOR_ID_URL,
) : VisitorIdSource {

    private val lock = Mutex()
    private var cached: String? = null

    override suspend fun current(): String? = lock.withLock {
        cached ?: fetch()?.also {
            cached = it
            Diag.log("sabr", "visitor id acquired for the embedded player (${it.length} chars)")
        }
    }

    private suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", WEB_VERSION)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com")
            .post(BODY.toRequestBody(JSON))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Diag.warn("sabr", "visitor_id -> HTTP ${response.code}; the embedded player cannot be asked")
                    return@withContext null
                }
                VisitorId.parse(response.body.string())
                    ?: null.also { Diag.warn("sabr", "visitor_id answered without a visitorData") }
            }
        } catch (e: IOException) {
            Diag.warn("sabr", "visitor_id could not be fetched", e)
            null
        }
    }

    public companion object {
        public const val VISITOR_ID_URL: String = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        private const val WEB_VERSION = "2.20260708.00.00"
        private val JSON = "application/json".toMediaType()
        private const val BODY =
            """{"context":{"client":{"clientName":"WEB","clientVersion":"$WEB_VERSION","clientScreen":"WATCH",""" +
                """"acceptLanguage":"en-US","acceptRegion":"US"},""" +
                """"user":{"enableSafetyMode":false,"lockedSafetyMode":false}},""" +
                """"racyCheckOk":true,"contentCheckOk":true}"""
    }
}

public object VisitorId {
    private val json = Json { ignoreUnknownKeys = true }

    /** `responseContext.visitorData` out of a `visitor_id` answer, or null. */
    public fun parse(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return (root["responseContext"] as? JsonObject)?.get(
            "visitorData"
        )?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    }
}
