package com.dewijones92.totum.data.notforme

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.ask.squashed
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.canonicalItemId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
public data class SuppressedItem(val id: String, val title: String, val by: String? = null, val atMs: Long)

public interface NotForMeStore {
    public fun load(): List<SuppressedItem>

    public fun save(items: List<SuppressedItem>)
}

public class InMemoryNotForMeStore(private var items: List<SuppressedItem> = emptyList()) : NotForMeStore {
    override fun load(): List<SuppressedItem> = items

    override fun save(items: List<SuppressedItem>) {
        this.items = items
    }
}

public class FileNotForMeStore(private val file: File) : NotForMeStore {
    override fun load(): List<SuppressedItem> = runCatching {
        if (!file.exists()) emptyList() else NotForMeJson.decodeFromString(LIST, file.readText())
    }.getOrElse {
        Diag.warn(TAG, "dewidebug the not-for-me list is unreadable, starting afresh: $it")
        emptyList()
    }

    override fun save(items: List<SuppressedItem>) {
        runCatching { file.writeTextReplacing(NotForMeJson.encodeToString(LIST, items)) }
            .onFailure { Diag.warn(TAG, "dewidebug the not-for-me list was not saved: $it") }
    }
}

public class NotForMe(
    private val store: NotForMeStore = InMemoryNotForMeStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow(store.load())
    public val items: StateFlow<List<SuppressedItem>> = state.asStateFlow()

    public fun contains(id: MediaItemId): Boolean {
        val wanted = canonicalItemId(id.value)
        return state.value.any { it.id == wanted }
    }

    public fun mentions(label: String): Boolean {
        val wanted = label.squashed()
        return wanted.isNotEmpty() && state.value.any { it.title.squashed() == wanted }
    }

    public fun titles(): List<String> = state.value.map { it.title }

    public fun suppress(item: MediaItem, from: String) {
        val id = canonicalItemId(item.id.value)
        if (state.value.any { it.id == id }) {
            Diag.log(TAG, "dewidebug ${item.id.value} is already not for me ($from)")
            return
        }
        state.update { listOf(SuppressedItem(id, item.title, item.author, clock())) + it }
        Diag.log(TAG, "dewidebug \"${item.title}\" (${item.id.value}) is not for me ($from); ${state.value.size} kept")
        store.save(state.value)
    }

    public fun allow(id: String, from: String) {
        val gone = state.value.firstOrNull { it.id == id }
        if (gone == null) {
            Diag.log(TAG, "dewidebug $id is not in the list ($from)")
            return
        }
        state.update { list -> list.filterNot { it.id == id } }
        Diag.log(TAG, "dewidebug \"${gone.title}\" ($id) allowed again ($from); ${state.value.size} kept")
        store.save(state.value)
    }

    public fun <T> without(list: List<T>, why: String, idOf: (T) -> MediaItemId): List<T> {
        if (state.value.isEmpty()) return list
        val kept = list.filterNot { contains(idOf(it)) }
        if (kept.size != list.size) {
            Diag.log(TAG, "dewidebug $why: ${list.size - kept.size} of ${list.size} left out as not for me")
        }
        return kept
    }

    public fun without(items: List<MediaItem>, why: String): List<MediaItem> = without(items, why) { it.id }

    public fun encode(): String = NotForMeJson.encodeToString(LIST, state.value)

    public fun mergeMissing(saved: List<SuppressedItem>): Int {
        val known = state.value.mapTo(HashSet()) { it.id }
        val missing = saved.filter { it.id !in known }
        if (missing.isEmpty()) return 0
        state.update { it + missing }
        Diag.log(TAG, "dewidebug restored ${missing.size} not-for-me item(s) from a backup; ${state.value.size} kept")
        store.save(state.value)
        return missing.size
    }

    public companion object {
        public fun decode(json: String): List<SuppressedItem> = NotForMeJson.decodeFromString(LIST, json)
    }
}

private const val TAG = "notforme"
private val NotForMeJson = Json { ignoreUnknownKeys = true }
private val LIST = ListSerializer(SuppressedItem.serializer())
