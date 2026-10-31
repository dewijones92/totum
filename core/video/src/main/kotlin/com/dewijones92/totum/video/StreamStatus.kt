package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class StreamStatus(private val client: OkHttpClient) {
    suspend fun of(url: HttpUrl): Int? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url.value).header("Range", "bytes=0-$PROBE_LAST_BYTE").get().build()
        try {
            client.newCall(request).execute().use { it.code }
        } catch (e: IOException) {
            Diag.warn("resolve", "could not check a stream before using it", e)
            null
        }
    }

    private companion object {
        const val PROBE_LAST_BYTE = 1023
    }
}
