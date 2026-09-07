package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The `encryptedHostFlags` the embedded player must present for a video, or null when unknown. */
public fun interface EmbedHostFlagsSource {
    public suspend fun forVideo(videoId: String): String?
}

/**
 * Reads the flags out of `https://www.youtube.com/embed/<id>?html5=1`, where the page's own player
 * config carries them (`WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER.encryptedHostFlags`) — the same
 * fetch SmartTube makes, with the same Referer. Per video, so cached per video; a value is good for as
 * long as the page would have used it, which is the session.
 */
public class HttpEmbedHostFlagsSource(
    private val client: OkHttpClient,
    private val embedUrl: (videoId: String) -> String = { "https://www.youtube.com/embed/$it?html5=1" },
) : EmbedHostFlagsSource {

    private val lock = Mutex()
    private val cache = object : LinkedHashMap<String, String>(CACHE_SIZE, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_SIZE
    }

    override suspend fun forVideo(videoId: String): String? {
        lock.withLock { cache[videoId] }?.let { return it }
        val html = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(embedUrl(videoId))
                .header("User-Agent", EMBED_PAGE_USER_AGENT)
                .header("Referer", REFERER)
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body.string()
                    } else {
                        Diag.warn("sabr", "embed page for $videoId -> HTTP ${response.code}; no host flags")
                        null
                    }
                }
            } catch (e: IOException) {
                Diag.warn("sabr", "embed page for $videoId could not be fetched; no host flags", e)
                null
            }
        } ?: return null
        val flags = EmbedHostFlags.parse(html)
        if (flags == null) {
            Diag.warn("sabr", "embed page for $videoId carried no encryptedHostFlags (${html.length} chars)")
            return null
        }
        lock.withLock { cache[videoId] = flags }
        return flags
    }

    private companion object {
        const val CACHE_SIZE = 32
        const val LOAD_FACTOR = 0.75f
        const val REFERER = "https://www.reddit.com/"
        const val EMBED_PAGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

public object EmbedHostFlags {
    private val FLAGS = Regex(""""encryptedHostFlags"\s*:\s*"([^"]+)"""")

    /** The first `encryptedHostFlags` value in an embed page, or null. */
    public fun parse(html: String): String? = FLAGS.find(html)?.groupValues?.get(1)
}
