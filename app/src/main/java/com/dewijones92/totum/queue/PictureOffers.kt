package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.pictureForSwitching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PictureOffer(val item: PlayableItem, val atMs: Long)

class PictureOffers internal constructor(
    private val clock: () -> Long,
    private val listening: () -> Boolean,
    private val soundOnly: (PlayableItem) -> Boolean,
) {
    private val _offer = MutableStateFlow<PictureOffer?>(null)
    val offer: StateFlow<PictureOffer?> = _offer.asStateFlow()

    internal fun startedAt(item: PlayableItem?) = item?.let { started(it, "a tap in the queue") }

    internal fun started(item: PlayableItem, how: String) {
        val id = item.item.id.value
        val picture = item.pictureForSwitching
        val refused = when {
            !listening() -> "already watching"
            soundOnly(item) -> "music, or its picture was given up on"
            picture != Picture.YES -> "picture=$picture"
            else -> null
        }
        if (refused != null) {
            if (_offer.value != null) dismiss("$how started $id, which gets no offer")
            Diag.log(TAG, "$how $id: no picture offered ($refused)")
            return
        }
        Diag.log(TAG, "$how $id: listening, so offering its picture: \"${item.item.title.take(TITLE_CHARS)}\"")
        _offer.value = PictureOffer(item, clock())
    }

    fun dismiss(why: String) {
        val was = _offer.value ?: return
        Diag.log(TAG, "picture offer for ${was.item.item.id.value} closed: $why")
        _offer.value = null
    }

    private companion object {
        const val TAG = "listen"
        const val TITLE_CHARS = 60
    }
}
