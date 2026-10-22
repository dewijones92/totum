package com.dewijones92.totum.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.dewijones92.totum.common.Diag
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

public object PlaybackCacheReport {
    internal val heldBytes = AtomicLong(-1)
    internal val fromDiskBytes = AtomicLong(0)
    internal val ignored = AtomicLong(0)
    internal val opensCached = AtomicLong(0)
    internal val opensDirect = AtomicLong(0)

    public fun state(): Map<String, String> = mapOf(
        "cache.heldMb" to heldBytes.get().let { if (it < 0) "not opened yet" else (it shr MB_SHIFT).toString() },
        "cache.readFromDiskMb" to (fromDiskBytes.get() shr MB_SHIFT).toString(),
        "cache.ignoredAfterErrorOrUnknownLength" to ignored.get().toString(),
        "cache.opens" to "${opensCached.get()} through the cache, ${opensDirect.get()} direct",
    )

    internal const val MB_SHIFT = 20
}

@UnstableApi
internal object PlaybackCache {

    private const val MB_SHIFT = PlaybackCacheReport.MB_SHIFT
    private const val KB_SHIFT = 10
    private const val MAX_BYTES = 1L shl 30
    private const val LOG_EVERY_BYTES = 4L shl 20
    private const val LOG_KEY_EVERY_MS = 5_000L
    private const val KEYS_REMEMBERED = 256
    private const val KEY_CHARS = 60

    @Volatile private var cache: SimpleCache? = null

    private val loggedAt = AtomicLong(0)
    private val keyLoggedAt = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun get(context: Context): SimpleCache = cache ?: SimpleCache(
        File(context.cacheDir, "playback"),
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(context),
    ).also {
        cache = it
        Diag.log("cache", "playback cache opening (up to ${MAX_BYTES shr MB_SHIFT} MB)")
        thread(name = "playback-cache-size", isDaemon = true) {
            val held = it.cacheSpace
            PlaybackCacheReport.heldBytes.set(held)
            Diag.log("cache", "playback cache open: ${held shr MB_SHIFT} MB held of ${MAX_BYTES shr MB_SHIFT} MB")
        }
    }

    fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val store = get(context)
        val cached = CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> keyOf(spec.uri.toString()) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(
                object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                        PlaybackCacheReport.heldBytes.set(cacheSizeBytes)
                        val total = PlaybackCacheReport.fromDiskBytes.addAndGet(cachedBytesRead)
                        val last = loggedAt.get()
                        if ((last == 0L || total - last >= LOG_EVERY_BYTES) && loggedAt.compareAndSet(last, total)) {
                            Diag.log(
                                "cache",
                                "played ${sizeOf(total)} from the playback cache so far " +
                                    "(it holds ${cacheSizeBytes shr MB_SHIFT} MB)",
                            )
                        }
                    }

                    override fun onCacheIgnored(reason: Int) {
                        PlaybackCacheReport.ignored.incrementAndGet()
                        Diag.log("cache", "playback cache skipped for the rest of this item: ${ignoredWhy(reason)}")
                    }
                },
            )
        return DataSource.Factory { Router(store, cached.createDataSource(), upstream.createDataSource()) }
    }

    private fun sizeOf(bytes: Long): String =
        if (bytes >= 1L shl MB_SHIFT) "${bytes shr MB_SHIFT} MB" else "${bytes shr KB_SHIFT} KB"

    private fun keyOf(uri: String): String = PlaybackCacheKeys.key(uri) ?: uri

    private fun ignoredWhy(reason: Int): String = when (reason) {
        CacheDataSource.CACHE_IGNORED_REASON_ERROR -> "a read from the cache failed, so it fetches directly"
        CacheDataSource.CACHE_IGNORED_REASON_UNSET_LENGTH -> "the stream's length is unknown, so it is not stored"
        else -> "reason $reason"
    }

    private fun logOpen(store: Cache, spec: DataSpec, cacheable: Boolean) {
        val uri = spec.uri.toString()
        val key = if (cacheable) keyOf(uri) else uri.substringBefore('?')
        val now = System.currentTimeMillis()
        val before = keyLoggedAt[key]
        if (before != null && now - before < LOG_KEY_EVERY_MS) return
        if (keyLoggedAt.size > KEYS_REMEMBERED) keyLoggedAt.clear()
        keyLoggedAt[key] = now
        val short = key.take(KEY_CHARS)
        if (!cacheable) {
            Diag.log("cache", "direct, not cached (live, manifest, loopback or not http): $short")
            return
        }
        val length = if (spec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else spec.length
        val held = runCatching { store.getCachedBytes(key, spec.position, length) }.getOrDefault(-1L)
        val from = if (held > 0) "${sizeOf(held)} held from here" else "nothing held from here"
        Diag.log("cache", "through the cache from ${spec.position}: $from [key=$short]")
    }

    private class Router(
        private val store: Cache,
        private val cached: DataSource,
        private val direct: DataSource,
    ) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val cacheable = PlaybackCacheKeys.cacheable(dataSpec.uri.toString())
            (if (cacheable) PlaybackCacheReport.opensCached else PlaybackCacheReport.opensDirect).incrementAndGet()
            runCatching { logOpen(store, dataSpec, cacheable) }
            val chosen = if (cacheable) cached else direct
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
