package com.dewijones92.totum.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.dewijones92.totum.common.Diag
import java.io.File

@UnstableApi
internal object PlaybackCache {

    private const val MB_SHIFT = 20
    private const val MAX_BYTES = 1L shl 30
    private const val LOG_EVERY_BYTES = 4L shl 20

    @Volatile private var cache: SimpleCache? = null

    @Volatile private var fromDiskBytes = 0L

    @Volatile private var loggedAt = 0L

    @Synchronized
    fun get(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "playback"),
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(context),
    ).also {
        cache = it
        Diag.log("cache", "playback cache open: ${it.cacheSpace shr MB_SHIFT} MB held of ${MAX_BYTES shr MB_SHIFT} MB")
    }

    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val cached = CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> PlaybackCacheKeys.key(spec.uri.toString()) ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(
                object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                        val first = fromDiskBytes == 0L && cachedBytesRead > 0
                        fromDiskBytes += cachedBytesRead
                        if (first || fromDiskBytes - loggedAt >= LOG_EVERY_BYTES) {
                            loggedAt = fromDiskBytes
                            Diag.log(
                                "cache",
                                "played ${fromDiskBytes shr MB_SHIFT} MB from the playback cache so far " +
                                    "(it holds ${cacheSizeBytes shr MB_SHIFT} MB)",
                            )
                        }
                    }

                    override fun onCacheIgnored(reason: Int) {
                        Diag.log("cache", "playback cache skipped for one request (reason $reason)")
                    }
                },
            )
        return DataSource.Factory { Router(cached.createDataSource(), upstream.createDataSource()) }
    }

    private class Router(private val cached: DataSource, private val direct: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val chosen = if (PlaybackCacheKeys.cacheable(dataSpec.uri.toString())) cached else direct
            current = chosen
            return chosen.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            requireNotNull(current) { "read before open" }.read(buffer, offset, length)

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

        override fun close() {
            current?.close()
            current = null
        }
    }
}
