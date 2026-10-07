package com.dewijones92.totum.data.net

import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Port for fetching a text document over HTTP (feeds, search APIs, …);
 * nothing above this layer talks HTTP directly.
 */
public fun interface HttpTextFetcher {
    public suspend fun fetch(url: HttpUrl): FetchResult
}

public sealed interface FetchResult {
    public data class Success(val body: String) : FetchResult
    public data class Failure(val detail: String, val httpStatus: Int? = null) : FetchResult
}

/** OkHttp-backed [HttpTextFetcher]. */
public class OkHttpTextFetcher(private val client: OkHttpClient) : HttpTextFetcher {

    override suspend fun fetch(url: HttpUrl): FetchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url.value).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    FetchResult.Success(body)
                } else {
                    FetchResult.Failure("HTTP ${response.code}", httpStatus = response.code)
                }
            }
        } catch (e: IOException) {
            FetchResult.Failure(e.message ?: "network error")
        }
    }
}
