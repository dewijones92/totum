package com.dewijones92.totum.playback.cast

public object HomeOnly {
    private const val JELLYFIN_ID_PREFIX = "jellyfin:"

    public fun why(uri: String, mediaId: String, homeHost: String?): String? = when {
        mediaId.startsWith(JELLYFIN_ID_PREFIX) -> "a Jellyfin item, which only the home server can serve"
        homeHost.isNullOrBlank() -> null
        hostOf(uri).equals(homeHost, ignoreCase = true) -> "its address is the home server's own ($homeHost)"
        else -> null
    }

    internal fun hostOf(uri: String): String? = runCatching { java.net.URI(uri).host }.getOrNull()
}
