package com.dewijones92.totum.innertube.browse

import com.dewijones92.totum.common.PageToken
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Finds the "load more" token in any InnerTube response.
 *
 * Deliberately shape-agnostic: YouTube puts continuations in several places and moves
 * them (a grid's `continuationItemRenderer`, a shelf's `nextContinuationData`, a
 * `reloadContinuationData` on some feeds), and the TV and WEB clients differ again. A
 * parser keyed to one path silently stops paginating when YouTube reshuffles — which is
 * indistinguishable from "you reached the end". So this walks the tree and takes the
 * **last** token it finds: continuations sit after the items they continue, and taking
 * the last one avoids picking up a nested shelf's token instead of the feed's.
 *
 * **Filter chips are skipped.** A search response carries a `chipCloudRenderer` of
 * filters ("Shorts", "Live", "Recently uploaded"), and every chip holds a continuation
 * token of its own — six of them, all sitting *after* the results list. Taking the last
 * token therefore picked a filter rather than page two, and following it returned a
 * result set this parser found nothing in: search paginated to an empty page and stopped.
 * A chip's token replaces the results rather than extending them, so it is never what
 * "load more" wants, in any response.
 */
public object Continuations {

    private val TOKEN_HOLDERS = listOf("continuationCommand", "nextContinuationData", "reloadContinuationData")

    /** Filter chips; their tokens replace the results rather than continuing them. */
    private val NOT_THE_NEXT_PAGE = setOf("chipCloudRenderer", "chipBarViewModel", "showEngagementPanelEndpoint")

    public fun find(root: JsonElement): PageToken? {
        var found: String? = null
        walk(root) { found = it }
        return found?.let(::PageToken)
    }

    private fun walk(node: JsonElement, onToken: (String) -> Unit) {
        when (node) {
            is JsonObject -> {
                TOKEN_HOLDERS.forEach { holder ->
                    (node[holder] as? JsonObject)?.tokenField()?.let(onToken)
                }
                node.forEach { (key, value) -> if (key !in NOT_THE_NEXT_PAGE) walk(value, onToken) }
            }
            is JsonArray -> node.forEach { walk(it, onToken) }
            else -> Unit
        }
    }

    /** `continuationCommand` calls it `token`; the older renderers call it `continuation`. */
    private fun JsonObject.tokenField(): String? =
        stringAt("token") ?: stringAt("continuation")

    private fun JsonObject.stringAt(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
}
