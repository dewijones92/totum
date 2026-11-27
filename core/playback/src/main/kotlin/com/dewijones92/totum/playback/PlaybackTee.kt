package com.dewijones92.totum.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.ByteRange
import com.dewijones92.totum.domain.PlayerSpot
import com.dewijones92.totum.domain.StreamTee
import com.dewijones92.totum.domain.StreamTeeFiller
import com.dewijones92.totum.domain.StreamTeePlan
import com.dewijones92.totum.domain.TeeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Suppress("TooManyFunctions")
@OptIn(markerClass = [UnstableApi::class])
public object PlaybackTee : StreamTee {

    private data class Offer(val key: String, val uri: Uri, val length: Long, val itag: String)

    private val offers = ConcurrentHashMap<String, Offer>()
    private val teeable = MutableStateFlow<Set<String>>(emptySet())
    private val networkBytes = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var spotOf: (String) -> PlayerSpot? = { null }

    @Volatile private var cacheOf: (() -> Cache)? = null

    @Volatile private var sourceOf: (() -> CacheDataSource)? = null

    public val teeableNow: StateFlow<Set<String>> = teeable.asStateFlow()

    public fun attach(spot: (String) -> PlayerSpot?) {
        spotOf = spot
    }

    internal fun install(store: () -> Cache, fillSource: () -> CacheDataSource) {
        cacheOf = store
        sourceOf = fillSource
    }

    public fun networkBytesFor(itemId: String): Long =
        networkBytes.filterKeys { it.startsWith("yt:$itemId:") }.values.sumOf { it.get() }

    internal fun noteOpen(uri: Uri) {
        val key = PlaybackCacheKeys.key(uri.toString())?.takeIf { it.startsWith("yt:") } ?: return
        if (uri.getQueryParameter("mime").orEmpty().startsWith("audio").not()) return
        val parts = key.split(':')
        val id = parts.getOrNull(ID_PART) ?: return
        val itag = parts.getOrNull(ITAG_PART) ?: return
        val length = parts.getOrNull(LENGTH_PART)?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val earlier = offers.put(id, Offer(key, uri, length, itag))
        if (earlier?.key != key) {
            teeable.value = offers.keys.toSet()
            Diag.log(
                "tee",
                "dewidebug $id: its audio (itag $itag, $length bytes) streams through the playback cache, " +
                    "so its download can be made from those bytes instead of fetching them again",
            )
        }
    }

    public fun whyNotTeeable(itemId: String): String =
        TeeVerdict.why(PlayRoutes.of(itemId), offers.containsKey(itemId))

    override fun canTee(itemId: String): Boolean = offers.containsKey(itemId)

    override suspend fun complete(
        itemId: String,
        target: File,
        progress: (heldBytes: Long, totalBytes: Long) -> Unit,
    ): TeeOutcome = withContext(Dispatchers.IO) { completeNow(itemId, target, progress) }

    private suspend fun completeNow(
        itemId: String,
        target: File,
        progress: (Long, Long) -> Unit,
    ): TeeOutcome {
        val offer = offers[itemId] ?: return TeeOutcome.Failed("no audio stream of $itemId has been opened")
        val store = cacheOf?.invoke() ?: return TeeOutcome.Failed("the playback cache is not open")
        val source = sourceOf ?: return TeeOutcome.Failed("no cache source is installed")
        val buffer = ByteArray(BUFFER_BYTES)
        var teeBytes = 0L
        val filler = StreamTeeFiller(
            plan = StreamTeePlan(offer.length),
            held = { heldRanges(store, offer.key) },
            spot = { spotOf(itemId) },
            fetch = { range -> teeBytes += runInterruptible { fetchRange(source(), offer, range, buffer) } },
            wait = { delay(WAIT_MS) },
            log = { Diag.log("tee", "dewidebug $itemId: $it") },
            progress = { held -> progress(held, offer.length) },
        )
        return try {
            val outcome = filler.run()
            if (!outcome.complete) return TeeOutcome.Failed("the filler ended without the whole stream")
            copyOut(source(), offer, target)
            TeeOutcome.Completed(offer.length, teeBytes, networkBytes[offer.key]?.get() ?: UNKNOWN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            offers.remove(itemId)
            teeable.value = offers.keys.toSet()
            TeeOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun heldRanges(store: Cache, key: String): List<ByteRange> =
        store.getCachedSpans(key).filter { it.isCached }.map { ByteRange(it.position, it.position + it.length) }

    private fun fetchRange(source: CacheDataSource, offer: Offer, range: ByteRange, buffer: ByteArray): Long {
        val spec = DataSpec.Builder().setUri(offer.uri).setKey(offer.key)
            .setPosition(range.start).setLength(range.length).build()
        var fetched = 0L
        CacheWriter(source, spec, buffer) { _, _, newBytes -> fetched += newBytes }.cache()
        return fetched
    }

    private fun copyOut(source: CacheDataSource, offer: Offer, target: File) {
        val spec = DataSpec.Builder().setUri(offer.uri).setKey(offer.key).setPosition(0).setLength(offer.length).build()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tee")
        DataSourceInputStream(source, spec).use { input -> temp.outputStream().use { input.copyTo(it) } }
        if (temp.length() != offer.length) {
            val got = temp.length()
            temp.delete()
            throw IOException("the copy is $got bytes, the stream is ${offer.length}")
        }
        target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    internal fun countingListener(keyOf: (DataSpec) -> String?): TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            if (!isNetwork) return
            keyOf(dataSpec)?.let { networkBytes.getOrPut(it) { AtomicLong() }.addAndGet(bytesTransferred.toLong()) }
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private const val ID_PART = 1
    private const val ITAG_PART = 2
    private const val LENGTH_PART = 3
    private const val BUFFER_BYTES = 64 * 1024
    private const val WAIT_MS = 2_000L
    private const val UNKNOWN = -1L
}
