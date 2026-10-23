package com.dewijones92.totum.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.dewijones92.totum.common.Diag
import java.util.concurrent.ConcurrentHashMap

internal object SideLoadedText {
    private const val REMEMBERED = 256

    private val uris = ConcurrentHashMap.newKeySet<String>()
    private val kept = ConcurrentHashMap.newKeySet<String>()

    fun remember(uri: String) {
        if (uris.size >= REMEMBERED) uris.clear()
        uris += uri
    }

    @UnstableApi
    fun cacheable(spec: DataSpec): DataSpec {
        val uri = spec.uri.toString()
        if (uri !in uris || spec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN == 0) return spec
        if (kept.add(uri)) {
            if (kept.size > REMEMBERED) kept.clear()
            Diag.log(
                "cache",
                "side-loaded subtitles kept in the playback cache although their length is unknown, so a " +
                    "seek or a rewind reads them from the disk: ${uri.substringBefore('?')}",
            )
        }
        return spec.buildUpon().setFlags(spec.flags and DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN.inv()).build()
    }
}
