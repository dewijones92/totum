package com.dewijones92.totum.playback

internal object PlaybackCacheKeys {

    private val NEVER = listOf(".m3u8", ".mpd", "/manifest/", "live=1", "sq=", "/live/")
    private val LOOPBACK = listOf("://127.0.0.1", "://localhost")

    fun cacheable(uri: String): Boolean {
        val lower = uri.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://")) &&
            LOOPBACK.none(lower::contains) && NEVER.none(lower::contains)
    }

    fun key(uri: String): String? {
        if (!cacheable(uri)) return null
        val query = uri.substringAfter('?', "")
        val params = query.split('&').mapNotNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to v }
        }.toMap()
        return when {
            uri.contains("googlevideo.com/videoplayback") && params["id"] != null && params["itag"] != null ->
                "yt:${params["id"]}:${params["itag"]}:${params["clen"].orEmpty()}"
            query.contains("totumToken=") -> {
                val kept = query.split('&').filterNot { it.startsWith("totumToken=") }.joinToString("&")
                uri.substringBefore('?') + "?" + kept
            }
            else -> uri
        }
    }
}
