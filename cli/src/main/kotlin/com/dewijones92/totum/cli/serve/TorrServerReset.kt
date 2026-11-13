package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadStrategy
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.isPermanent
import com.dewijones92.totum.domain.isTorrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

internal class TorrServerReset(private val client: OkHttpClient) {

    suspend fun reset(item: PlayableItem) {
        val url = item.item.mediaUrl?.value ?: return
        val base = url.substringBefore("/stream/", missingDelimiterValue = "").ifBlank { return }
        val hash = HASH.find(url)?.groupValues?.get(1) ?: return
        val title = item.item.title
        withContext(Dispatchers.IO) {
            val dropped = post("$base/torrents", """{"action":"drop","hash":"$hash"}""")
            val added = post("$base/torrents", """{"action":"add","link":"$hash","save_to_db":false}""")
            Diag.log(
                "home",
                "\"$title\": TorrServer asked to drop and re-add ${hash.take(SHORT)} before the retry " +
                    "(drop: $dropped, add: $added)",
            )
        }
    }

    private fun post(url: String, body: String): String = try {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
        client.newCall(request).execute().use { "HTTP ${it.code}" }
    } catch (e: IOException) {
        Diag.warn("home", "TorrServer ${url.substringAfterLast('/')} could not be reached", e)
        "not reached: ${e.message}"
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val HASH = Regex("""[?&]link=([0-9A-Za-z]+)""")
        const val SHORT = 12
    }
}

internal class ResetsTorrentsOnFailure(
    private val inner: DownloadStrategy,
    private val reset: suspend (PlayableItem) -> Unit,
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
        afterFailure(item, inner.download(item, target, audioOnly))

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean, maxHeight: Int?): Flow<DownloadState> =
        afterFailure(item, inner.download(item, target, audioOnly, maxHeight))

    private fun afterFailure(item: PlayableItem, states: Flow<DownloadState>): Flow<DownloadState> =
        if (!item.item.isTorrent) {
            states
        } else {
            states.onEach { state ->
                if (state is DownloadState.Failed && !state.isPermanent) reset(item)
            }
        }
}
