package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag

internal class CastLoadHold<T>(private val now: () -> Long, private val holdMs: Long = HOLD_MS) {

    private class Asked<T>(val id: String, val item: T, val startMs: Long, val at: Long)

    private var asked: Asked<T>? = null
    private var lastLocal: Asked<T>? = null
    private var announced: String? = null
    private var sentLast: Asked<T>? = null
    private var unnamedSince: String? = null

    fun asked(id: String, item: T, startMs: Long) {
        asked = Asked(id, item, startMs, now())
        sentLast = asked
    }

    fun seen(id: String, item: T, localPositionMs: Long?) {
        asked?.takeIf { it.id == id }?.let {
            if (announced == id) Diag.log("cast", "$id reached the receiver ${now() - it.at}ms after it was asked for")
            asked = null
            announced = null
        }
        if (localPositionMs != null) {
            lastLocal = Asked(id, item, localPositionMs, now())
        } else {
            sentLast = Asked(id, item, 0L, now())
        }
        unnamedSince = null
    }

    fun loading(): Pair<T, Long>? {
        val waiting = asked ?: lastLocal?.also {
            asked = it
            lastLocal = null
        }
        val fresh = waiting?.takeIf { now() - it.at < holdMs }
        if (fresh == null) {
            if (announced != null) {
                Diag.log(
                    "cast",
                    "stopped showing $announced as loading: the receiver still reports nothing"
                )
            }
            asked = null
            announced = null
            return null
        }
        if (announced != fresh.id) {
            Diag.log(
                "cast",
                "${fresh.id}: the receiver reports nothing while it loads; showing it as loading (up to ${holdMs}ms)",
            )
            announced = fresh.id
        }
        return fresh.item to fresh.startMs
    }

    fun unnamed(): T? {
        val stand = asked ?: sentLast ?: lastLocal
        if (stand != null && unnamedSince != stand.id) {
            Diag.warn(
                "cast",
                "the receiver plays an item Media3 lost the name of; showing ${stand.id}, the one Totum sent last",
            )
            unnamedSince = stand.id
        }
        return stand?.item
    }

    fun forgetLocal() {
        lastLocal = null
    }

    companion object {
        const val HOLD_MS: Long = 30_000L
    }
}
