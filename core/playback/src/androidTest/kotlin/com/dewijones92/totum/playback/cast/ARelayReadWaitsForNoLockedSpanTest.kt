package com.dewijones92.totum.playback.cast

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.playback.PlaybackCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ARelayReadWaitsForNoLockedSpanTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Upstream(private val data: ByteArray) : DataSource.Factory {
        override fun createDataSource(): DataSource = object : BaseDataSource(true) {
            private var position = 0L
            private var remaining = 0L
            private var opened: Uri? = null

            override fun open(dataSpec: DataSpec): Long {
                transferInitializing(dataSpec)
                position = dataSpec.position
                remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size - position else dataSpec.length
                opened = dataSpec.uri
                transferStarted(dataSpec)
                return remaining
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                val n = minOf(length.toLong(), remaining).toInt()
                data.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                position += n
                remaining -= n
                bytesTransferred(n)
                return n
            }

            override fun getUri(): Uri? = opened

            override fun close() {
                transferEnded()
            }
        }
    }

    private fun answered(relay: CachedUpstream, url: String, data: ByteArray): Pair<Long, UpstreamReply?> {
        val reply = AtomicReference<UpstreamReply?>()
        val failure = AtomicReference<Throwable?>()
        val begun = System.currentTimeMillis()
        val asker = Thread {
            try {
                reply.set(relay.get(url, "bytes=$HELD_FROM-"))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }.apply { isDaemon = true }
        asker.start()
        asker.join(WAIT_MS)
        val took = System.currentTimeMillis() - begun
        assertTrue("the relay is still blocked on the locked span after ${took}ms", !asker.isAlive)
        assertEquals(null, failure.get())
        assertEquals(PARTIAL, reply.get()?.status)
        val bytes = reply.get()!!.body().use { it.readBytes() }
        assertEquals(data.size - HELD_FROM.toInt(), bytes.size)
        assertEquals(data[HELD_FROM.toInt()], bytes.first())
        return took to reply.get()
    }

    private fun lockedStore(id: String, key: String) =
        SimpleCache(File(context.cacheDir, "relay-lock-$id"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
            .let { store -> store to store.startReadWrite(key, HELD_FROM, 1L) }

    private fun urlOf(id: String, size: Int) =
        "https://rr1.googlevideo.com/videoplayback?id=$id&itag=251&clen=$size&mime=audio%2Fwebm"

    @Test
    fun a_range_whose_span_another_reader_holds_is_read_from_upstream_at_once_by_the_relay_factory() {
        val id = "lock${System.nanoTime()}"
        val data = ByteArray(DATA_BYTES) { (it % 251).toByte() }
        val (store, held) = lockedStore(id, "yt:$id:251:${data.size}:")
        assertNotNull("the test holds the span the relay will ask for", held)
        val relay = CachedUpstream(PlaybackCache.relayFactory(store, Upstream(data)), Upstream(data))

        val (took, _) = answered(relay, urlOf(id, data.size), data)

        store.releaseHoleSpan(held)
        assertTrue("answered in ${took}ms, which is the budget's worth of waiting", took < FAST_MS)
    }

    @Test
    fun a_cache_that_would_block_is_given_up_on_after_the_budget_and_upstream_is_read_directly() {
        val id = "budget${System.nanoTime()}"
        val data = ByteArray(DATA_BYTES) { (it % 251).toByte() }
        val (store, held) = lockedStore(id, "yt:$id:251:${data.size}:")
        assertNotNull("the test holds the span the relay will ask for", held)
        val relay = CachedUpstream(
            PlaybackCache.factory(store, Upstream(data)),
            Upstream(data),
            BudgetedOpen(budgetMs = SHORT_BUDGET_MS),
        )

        val (took, _) = answered(relay, urlOf(id, data.size), data)

        store.releaseHoleSpan(held)
        assertTrue("took ${took}ms, the budget was ${SHORT_BUDGET_MS}ms", took in SHORT_BUDGET_MS until WAIT_MS)
    }

    private companion object {
        const val DATA_BYTES = 200_000
        const val HELD_FROM = 100_000L
        const val WAIT_MS = 9_000L
        const val FAST_MS = 2_000L
        const val SHORT_BUDGET_MS = 800L
        const val PARTIAL = 206
    }
}
