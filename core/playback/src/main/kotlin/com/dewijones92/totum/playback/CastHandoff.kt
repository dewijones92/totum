package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag

internal class CastHandoff<T : Any>(private val idOf: (T) -> String) {

    var item: T? = null
        private set
    var positionMs: Long = 0
        private set

    private var armed: T? = null

    fun started(item: T, positionMs: Long, why: String) {
        if (this.item?.let(idOf) != idOf(item) || this.positionMs != positionMs) {
            Diag.log("cast", "hand-over now ${idOf(item)} at ${positionMs}ms ($why)")
        }
        this.item = item
        this.positionMs = positionMs.coerceAtLeast(0)
        armed = null
    }

    fun armed(item: T) {
        armed = item
        Diag.log("cast", "hand-over: ${idOf(item)} waits behind ${this.item?.let(idOf)} on the TV")
    }
    fun crossed(why: String): Boolean {
        val next = armed ?: run {
            Diag.log("cast", "hand-over: the TV moved on ($why), but nothing was waiting behind ${item?.let(idOf)}")
            return false
        }
        started(next, 0, "the TV crossed over to it ($why)")
        return true
    }

    fun progressed(positionMs: Long) {
        if (item != null) this.positionMs = positionMs.coerceAtLeast(0)
    }

    fun sourceOf(receiverItem: T?): String = when {
        receiverItem == null -> "the one kept while casting (the receiver had none)"
        idOf(receiverItem).isBlank() -> "the one Totum sent (the receiver names nothing)"
        else -> "the one the player has"
    }

    fun itemFor(receiverItem: T?): T? = receiverItem?.takeIf { idOf(it).isNotBlank() } ?: item
}
