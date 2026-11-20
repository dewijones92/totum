package com.dewijones92.totum.data.net

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class TimedCacheFetcher(
    private val delegate: HttpTextFetcher,
    private val label: String,
    private val ttlMs: Long,
    private val maxEntries: Int,
    private val cacheable: (HttpUrl) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : HttpTextFetcher {

    private class Entry(val body: String, val storedAtMs: Long)

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private var hits = 0
    private var misses = 0

    override suspend fun fetch(url: HttpUrl): FetchResult {
        if (!cacheable(url)) return delegate.fetch(url)
        val now = nowMs()
        lock.withLock {
            entries[url.value]?.takeIf { now - it.storedAtMs < ttlMs }?.let { fresh ->
                hits++
                val age = (now - fresh.storedAtMs) / MS_A_SECOND
                Diag.log(label, "cache hit (${age}s old; hits=$hits misses=$misses) $url")
                return FetchResult.Success(fresh.body)
            }
        }
        val fetched = delegate.fetch(url)
        lock.withLock {
            misses++
            if (fetched is FetchResult.Success) {
                entries.remove(url.value)
                entries[url.value] = Entry(fetched.body, nowMs())
                while (entries.size > maxEntries) entries.remove(entries.keys.first())
            }
            val outcome = if (fetched is FetchResult.Success) "stored" else "not stored: $fetched"
            Diag.log(label, "cache miss -> $outcome (hits=$hits misses=$misses held=${entries.size}) $url")
        }
        return fetched
    }

    private companion object {
        const val MS_A_SECOND = 1_000L
    }
}
