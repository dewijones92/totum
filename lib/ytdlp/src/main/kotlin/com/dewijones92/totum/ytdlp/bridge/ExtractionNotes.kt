package com.dewijones92.totum.ytdlp.bridge

import com.dewijones92.totum.common.Diag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * yt-dlp's own account of anything it lost on the way, in the one place every engine passes through.
 *
 * Its own file because `BridgeJson.kt` is at detekt's function limit, and because both the extraction
 * and the download contracts carry these: a second inline copy of the rule is the duplication this
 * whole change was about removing. The reporting lived in `ChaquopyYtDlpEngine` — one of two engines —
 * so the desktop engine, which runs the same bridge script through the same parser, carried the notes
 * in its JSON and dropped them.
 */
internal fun JsonObject.notes(): List<String> =
    (this["notes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

/**
 * Warned rather than logged, because a missing JavaScript runtime or an abandoned client is the
 * difference between a stream that plays to the end and one that stops a megabyte in. Silent when
 * there is nothing to say, so the line means something when it appears.
 *
 * [what] names the operation, because "n challenge solving failed" is a different problem when a
 * DOWNLOAD reports it than when a resolve does.
 */
internal fun reportNotes(what: String, notes: List<String>) {
    if (notes.isEmpty()) return
    Diag.warn("engine", "yt-dlp reported ${notes.size} note(s) while $what: ${notes.joinToString(" | ")}")
}
