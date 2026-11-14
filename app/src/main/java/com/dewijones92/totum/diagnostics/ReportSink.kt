package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal class ReportSink(
    private val client: OkHttpClient,
    private val endpoint: () -> String? = HomeServerAuth::reportEndpoint,
) {
    fun post(body: String, what: String): Boolean = postReport(body, what).accepted

    fun postReport(body: String, what: String): SinkReply = send(body.toRequestBody(JSON), what, "", null)

    fun postState(gzipped: ByteArray, what: String, reportId: String?): SinkReply =
        send(gzipped.toRequestBody(GZIP), what, STATE_PATH, reportId)

    private fun send(body: RequestBody, what: String, path: String, reportId: String?): SinkReply {
        val url = endpoint()?.let { it + path }?.toHttpUrlOrNull()
        if (url == null) {
            Diag.log("diagnostics", "$what not posted: no home server is set up, so there is no sink to post to")
            return SinkReply.NOT_POSTED
        }
        val headers = HomeServerAuth.headersFor(url.host)
        if (headers.isEmpty()) {
            Diag.log(
                "diagnostics",
                "$what not posted: ${url.host} is not the configured home server's sink, or this phone has no token",
            )
            return SinkReply.NOT_POSTED
        }
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .apply { reportId?.let { header(REPORT_ID_HEADER, it) } }
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                Diag.log("diagnostics", "$what -> ${url.host} HTTP ${response.code}")
                SinkReply(response.code, response.peekBody(MAX_REPLY_BYTES).string())
            }
        } catch (e: IOException) {
            Diag.warn("diagnostics", "$what post to ${url.host} failed", e)
            SinkReply.NOT_POSTED
        }
    }

    companion object {
        const val REPORT_ID_HEADER = "X-Totum-Report-Id"
        private val JSON = "application/json".toMediaType()
        private val GZIP = "application/gzip".toMediaType()
        private const val STATE_PATH = "/state"
        private const val MAX_REPLY_BYTES = 512L
    }
}

internal data class SinkReply(val code: Int?, val body: String?) {
    val accepted: Boolean get() = code != null && code in HTTP_OK_RANGE

    val retryable: Boolean
        get() = code == null || code >= HTTP_SERVER_ERROR || code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS

    fun reportId(): String? = body?.let { REPORT_ID_IN_REPLY.find(it)?.groupValues?.get(1) }

    companion object {
        val NOT_POSTED = SinkReply(null, null)
        private val HTTP_OK_RANGE = 200..299
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val REPORT_ID_IN_REPLY = Regex("\"id\"\\s*:\\s*\"(\\d{8}T\\d{6}-[0-9a-f]{8})\"")
    }
}
