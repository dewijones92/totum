package com.dewijones92.totum.data.phone

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PhoneMediaEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

public data class PhoneMediaListState(
    val query: PhoneMediaQuery? = null,
    val entries: List<PhoneMediaEntry> = emptyList(),
    val next: Int? = null,
    val loading: Boolean = false,
    val failure: String? = null,
) {
    val hasMore: Boolean get() = next != null
    val empty: Boolean get() = query != null && !loading && failure == null && entries.isEmpty()
}

public class PhoneMediaBrowser(private val source: PhoneMediaSource) {

    private val mutable = MutableStateFlow(PhoneMediaListState())
    public val state: StateFlow<PhoneMediaListState> = mutable.asStateFlow()

    private val generation = AtomicLong()

    @Suppress("TooGenericExceptionCaught")
    public suspend fun open(query: PhoneMediaQuery): Int {
        val mine = generation.incrementAndGet()
        mutable.value = PhoneMediaListState(query = query.copy(offset = 0), loading = true)
        Diag.log(
            "phone-media",
            "open ${query.kind} folder=${query.folder} search=\"${query.search}\" sort=${query.sort}"
        )
        return try {
            val page = source.page(query.copy(offset = 0))
            if (generation.get() != mine) return 0
            mutable.value = PhoneMediaListState(
                query = query.copy(offset = 0),
                entries = page.entries,
                next = page.next,
            )
            Diag.log("phone-media", "opened ${query.kind}: ${page.entries.size} rows, next=${page.next}")
            page.entries.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Diag.warn("phone-media", "opening ${query.kind} failed", failure)
            if (generation.get() == mine) {
                mutable.update { it.copy(loading = false, failure = failure.message ?: failure.javaClass.simpleName) }
            }
            0
        }
    }

    @Suppress("TooGenericExceptionCaught")
    public suspend fun more(): Int {
        val before = mutable.value
        val query = before.query ?: return 0
        val offset = before.next
        if (before.loading || offset == null) {
            Diag.log(
                "phone-media",
                "not loading more: loading=${before.loading} next=$offset (${before.entries.size} rows shown)",
            )
            return 0
        }
        val mine = generation.get()
        mutable.update { it.copy(loading = true, failure = null) }
        return try {
            val page = source.page(query.copy(offset = offset))
            if (generation.get() != mine) return 0
            val known = mutable.value.entries.mapTo(HashSet()) { it.id }
            val fresh = page.entries.filter { it.id !in known }
            mutable.update { it.copy(entries = it.entries + fresh, next = page.next, loading = false) }
            Diag.log(
                "phone-media",
                "more of ${query.kind} from $offset: ${page.entries.size} rows, ${fresh.size} new, next=${page.next}",
            )
            fresh.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Diag.warn("phone-media", "more of ${query.kind} from $offset failed", failure)
            if (generation.get() == mine) {
                mutable.update { it.copy(loading = false, failure = failure.message ?: failure.javaClass.simpleName) }
            }
            0
        }
    }
}
