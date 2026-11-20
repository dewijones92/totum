package com.dewijones92.totum.innertube.channel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal object ChannelTabs {

    private const val TAB_RENDERER = "\"tabRenderer\""
    private val LIVE_TAB_URL = Regex("\"url\":\"[^\"]*/streams\"")

    fun listsTabs(body: String): Boolean = body.contains(TAB_RENDERER)

    fun listsLiveTab(body: String): Boolean = LIVE_TAB_URL.containsMatchIn(body)

    fun liveTabIsSelected(root: JsonElement): Boolean {
        val contents = (root as? JsonObject)?.get("contents") as? JsonObject
        val browse = contents?.get("twoColumnBrowseResultsRenderer") as? JsonObject
        val tabs = browse?.get("tabs") as? JsonArray ?: return false
        return tabs.any { tab ->
            val renderer = (tab as? JsonObject)?.get("tabRenderer") as? JsonObject ?: return@any false
            val selected = (renderer["selected"] as? JsonPrimitive)?.booleanOrNull == true
            selected && renderer.url()?.endsWith("/streams") == true
        }
    }

    private fun JsonObject.url(): String? {
        val metadata = (this["endpoint"] as? JsonObject)?.get("commandMetadata") as? JsonObject
        val web = metadata?.get("webCommandMetadata") as? JsonObject
        return (web?.get("url") as? JsonPrimitive)?.contentOrNull
    }
}
