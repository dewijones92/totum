package com.dewijones92.totum.queue

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class TrailNavigation(
    private val trail: PlayTrail,
    private val queue: PlaybackQueue,
    private val scope: CoroutineScope,
) {
    val around: StateFlow<PlayTrail.Place> get() = trail.around

    val listing: StateFlow<PlayTrail.Listing> get() = trail.listing

    fun start() {
        scope.launch {
            queue.nowPlaying.filterNotNull().distinctUntilChangedBy { it.item.id }.collect(trail::noted)
        }
    }

    fun snapshot(): Pair<List<PlayableItem>, Int> = trail.snapshot()

    fun back() = go("back", trail.back())

    fun forward() = go("forward", trail.forward())

    fun goTo(index: Int) = go("jump", trail.goTo(index))

    private fun go(way: String, item: PlayableItem?) {
        item ?: return Diag.log("trail", "$way: nothing there")
        scope.launch {
            val index = queue.state.value.entries.indexOfFirst { it.item.item.id == item.item.id }
            if (index >= 0) {
                Diag.log("trail", "$way to \"${item.item.title}\", row $index of the queue")
                queue.jumpTo(index, reason = PlayReason.Chosen(ChosenWay.BACK_OR_FORWARD))
            } else {
                Diag.log("trail", "$way to \"${item.item.title}\", no longer queued; playing it now")
                queue.playNow(item, reason = PlayReason.Chosen(ChosenWay.BACK_OR_FORWARD))
            }
        }
    }
}

class PrefsTrailStore(context: Context) : TrailStore {
    private val prefs = context.getSharedPreferences("totum_play_trail", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(BackupItem.serializer())

    override fun load(): Pair<List<PlayableItem>, Int> = runCatching {
        val items = json.decodeFromString(list, prefs.getString(ITEMS, "[]").orEmpty()).mapNotNull { it.toPlayable() }
        items to prefs.getInt(CURSOR, items.lastIndex)
    }.getOrElse {
        Diag.warn("trail", "the saved trail could not be read; starting empty", it)
        emptyList<PlayableItem>() to -1
    }

    override fun save(items: List<PlayableItem>, cursor: Int) {
        prefs.edit {
            putString(ITEMS, json.encodeToString(list, items.map { it.toBackup() }))
            putInt(CURSOR, cursor)
        }
    }

    private companion object {
        const val ITEMS = "items"
        const val CURSOR = "cursor"
    }
}
