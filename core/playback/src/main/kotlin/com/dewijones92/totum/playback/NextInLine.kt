package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag

internal class NextInLine {

    data class Armed(val context: ItemContext, val uri: String, val audioUri: String?)

    var armed: Armed? = null
        private set
    private var adoptable: Armed? = null

    fun arm(next: Armed) {
        val replaced = armed
        armed = next
        Diag.log(
            "gapless",
            "armed ${next.context.id}" + (replaced?.let { " (replacing ${it.context.id})" } ?: "") +
                " — ${next.uri.forLog()}" + (next.audioUri?.let { " + audio ${it.forLog()}" } ?: ""),
        )
    }

    fun disarm(reason: String): Armed? {
        val was = armed ?: return null
        armed = null
        Diag.log("gapless", "disarmed ${was.context.id}: $reason")
        return was
    }

    fun crossedTo(mediaId: String?): Armed? {
        val next = armed?.takeIf { it.context.id == mediaId } ?: return null
        armed = null
        adoptable = next
        return next
    }

    fun adopt(id: String, uri: String, audioUri: String?): Boolean {
        val candidate = adoptable ?: return false
        adoptable = null
        val matches = candidate.context.id == id && candidate.uri == uri && candidate.audioUri == audioUri
        Diag.log(
            "gapless",
            if (matches) {
                "adopted $id: the player is already on it, nothing rebuilt"
            } else {
                "not adopting $id: the queue asked for ${uri.forLog()}" +
                    (audioUri?.let { " + ${it.forLog()}" } ?: "") + " but the player crossed to " +
                    "${candidate.context.id} ${candidate.uri.forLog()}; rebuilding"
            },
        )
        return matches
    }

    fun forgetOnRebuild(id: String) {
        val dropped = armed
        armed = null
        adoptable = null
        if (dropped != null) Diag.log("gapless", "a rebuild for $id dropped the armed ${dropped.context.id}")
    }
}
