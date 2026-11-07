package com.dewijones92.totum.cli.serve.jellyfin

import com.dewijones92.totum.cli.serve.filing.Jellyfin
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Response

internal class JellyfinFailure(message: String) : RuntimeException(message)

internal class Page(val items: List<JellyfinItem>, val total: Int?, val returned: Int)

internal class JellyfinBrowse(private val jellyfin: Jellyfin) {

    fun listing(parent: String?, start: Int = 0): JellyfinListing {
        val where = "${parent ?: "top level"} from $start"
        val path = (if (parent == null) TOP_LEVEL else children(parent)) + "&Limit=$PAGE&StartIndex=$start"
        val body = jellyfin.get(path)
        if (body == null) {
            Diag.warn("jellyfin", "browse $where: Jellyfin did not answer")
            throw JellyfinFailure("❌ Jellyfin did not answer")
        }
        val page = parsePage(body)
        if (page == null) {
            Diag.warn("jellyfin", "browse $where: the answer was not a library listing: ${body.take(LOGGED)}")
            throw JellyfinFailure("❌ Jellyfin's answer was not a library listing (is its key right?)")
        }
        val items = page.items
        val total = page.total
        val end = start + page.returned
        val next = end.takeIf { page.returned > 0 && total != null && it < total }
        Diag.log("jellyfin", "browse $where: ${items.size} item(s) of ${total ?: "an unknown number"}, next=$next")
        return JellyfinListing(
            parent,
            if (parent == null) "🎞️ Films and series" else titleOf(items),
            items,
            total,
            next,
        )
    }

    fun open(id: String, range: String?): Response? = jellyfin.open("/Videos/$id/stream?static=true", range)

    private fun children(parent: String): String =
        "/Items?ParentId=$parent&SortBy=ParentIndexNumber,IndexNumber,SortName&Fields=$FIELDS"

    private fun titleOf(items: List<JellyfinItem>): String {
        val first = items.firstOrNull() ?: return "📭 Nothing here"
        return when (first.kind) {
            JellyfinKind.SEASON -> "📺 ${first.seriesName ?: "Seasons"}"
            JellyfinKind.EPISODE -> "📁 ${first.seriesName ?: "Episodes"}"
            else -> "📁 Folder"
        }
    }

    companion object {
        private const val FIELDS = "Overview,ProductionYear,MediaSources,SeriesName"
        private const val TOP_LEVEL =
            "/Items?Recursive=true&IncludeItemTypes=Movie,Series&SortBy=SortName&Fields=$FIELDS"
        private const val TICKS_PER_MS = 10_000L

        private const val PAGE = 100
        private const val LOGGED = 200

        fun parse(body: String): List<JellyfinItem> = parsePage(body)?.items.orEmpty()

        fun parsePage(body: String): Page? {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val items = root["Items"]?.jsonArray ?: return null
            val total = root["TotalRecordCount"]?.jsonPrimitive?.intOrNull
            return Page(items.mapNotNull { (it as? JsonObject)?.let(::itemOf) }, total, items.size)
        }

        private fun itemOf(obj: JsonObject): JellyfinItem? {
            val id = obj.text("Id") ?: return null
            val name = obj.text("Name") ?: return null
            val kind = when (obj.text("Type")) {
                "Movie" -> JellyfinKind.MOVIE
                "Series" -> JellyfinKind.SERIES
                "Season" -> JellyfinKind.SEASON
                "Episode" -> JellyfinKind.EPISODE
                else -> JellyfinKind.OTHER
            }
            return JellyfinItem(
                id = id,
                name = name,
                kind = kind,
                overview = obj.text("Overview"),
                year = obj.number("ProductionYear")?.toInt(),
                runtimeMs = obj.number("RunTimeTicks")?.div(TICKS_PER_MS),
                seriesName = obj.text("SeriesName"),
                seasonNumber = if (kind == JellyfinKind.EPISODE) obj.number("ParentIndexNumber")?.toInt() else null,
                episodeNumber = if (kind == JellyfinKind.EPISODE) obj.number("IndexNumber")?.toInt() else null,
                container = obj.text("Container"),
            )
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonElement)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.jsonPrimitive?.contentOrNull?.ifBlank { null }

        private fun JsonObject.number(key: String): Long? =
            this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive
                ?.let { it.longOrNull ?: it.intOrNull?.toLong() }
    }
}
