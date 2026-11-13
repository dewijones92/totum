package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TrailStore {
    fun load(): Pair<List<PlayableItem>, Int>
    fun save(items: List<PlayableItem>, cursor: Int)
}

object NoTrailStore : TrailStore {
    override fun load(): Pair<List<PlayableItem>, Int> = emptyList<PlayableItem>() to -1
    override fun save(items: List<PlayableItem>, cursor: Int) = Unit
}

class PlayTrail(private val store: TrailStore = NoTrailStore, private val limit: Int = LIMIT) {

    data class Place(val back: PlayableItem?, val forward: PlayableItem?)

    data class Listing(val items: List<PlayableItem>, val cursor: Int)

    private var items: List<PlayableItem>
    private var cursor: Int
    private var arriving: MediaItemId? = null
    private val place = MutableStateFlow(Place(null, null))
    val around: StateFlow<Place> = place.asStateFlow()
    private val everything = MutableStateFlow(Listing(emptyList(), -1))
    val listing: StateFlow<Listing> = everything.asStateFlow()

    init {
        val (saved, at) = store.load()
        items = saved
        cursor = at.coerceIn(-1, saved.lastIndex)
        publish()
    }

    @Synchronized
    fun noted(item: PlayableItem) {
        val id = item.item.id
        if (items.getOrNull(cursor)?.item?.id == id) return
        if (arriving == id) {
            arriving = null
            cursor = items.indexOfFirst { it.item.id == id }.takeIf { it >= 0 } ?: cursor
            Diag.log("trail", "arrived at ${id.value} (${cursor + 1} of ${items.size})")
        } else {
            val kept = items.take(cursor + 1).filterNot { it.item.id == id }
            items = (kept + item).takeLast(limit)
            cursor = items.lastIndex
            Diag.log("trail", "played ${id.value}; ${items.size} in the trail, nothing ahead")
        }
        store.save(items, cursor)
        publish()
    }

    @Synchronized
    fun snapshot(): Pair<List<PlayableItem>, Int> = items to cursor

    @Synchronized
    fun back(): PlayableItem? = step(-1)

    @Synchronized
    fun forward(): PlayableItem? = step(1)

    @Synchronized
    fun goTo(index: Int): PlayableItem? {
        val target = items.getOrNull(index)
        if (target == null || index == cursor) {
            Diag.log("trail", "go to place ${index + 1} of ${items.size}: nothing to go to (at ${cursor + 1})")
            return null
        }
        arriving = target.item.id
        Diag.log("trail", "jump from place ${cursor + 1} to ${index + 1} of ${items.size}: ${target.item.id.value}")
        return target
    }

    private fun step(by: Int): PlayableItem? {
        val target = items.getOrNull(cursor + by) ?: return null
        arriving = target.item.id
        Diag.log("trail", "${if (by < 0) "back" else "forward"} to ${target.item.id.value}")
        return target
    }

    private fun publish() {
        place.value = Place(items.getOrNull(cursor - 1), items.getOrNull(cursor + 1))
        everything.value = Listing(items, cursor)
    }

    private companion object {
        const val LIMIT = 50
    }
}
