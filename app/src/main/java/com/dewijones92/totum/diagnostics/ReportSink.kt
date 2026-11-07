package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal class ReportSink(
    private val client: OkHttpClient,
    private val endpoint: () -> String? = HomeServerAuth::reportEndpoint,
) {
    fun post(body: String, what: String): Boolean {
        val url = endpoint()?.toHttpUrlOrNull()
        if (url == null) {
            Diag.log("diagnostics", "$what not posted: no home server is set up, so there is no sink to post to")
            return false
        }
        val headers = HomeServerAuth.headersFor(url.host)
        if (headers.isEmpty()) {
            Diag.log(
                "diagnostics",
                "$what not posted: ${url.host} is not the configured home server's sink, or this phone has no token",
            )
            return false
        }
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                Diag.log("diagnostics", "$what -> ${url.host} HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: IOException) {
            Diag.warn("diagnostics", "$what post to ${url.host} failed", e)
            false
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
