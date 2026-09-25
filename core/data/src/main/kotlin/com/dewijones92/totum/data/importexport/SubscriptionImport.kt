package com.dewijones92.totum.data.importexport

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.findYouTubeChannelId
import com.dewijones92.totum.common.youTubeChannelUrl
import com.dewijones92.totum.data.xml.descendantsNamed
import com.dewijones92.totum.data.xml.hardenedDocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

/**
 * A subscription found in an imported file, before it is applied. Pillar-tagged
 * so the importer routes each to the right subscribe path (podcast feed vs the
 * live YouTube account) without the parser knowing either.
 */
public sealed interface ImportedSource {
    public val title: String

    public data class Podcast(override val title: String, val feedUrl: HttpUrl) : ImportedSource

    /**
     * [channelUrl] is normalised to `/channel/UC…` when a channel id was found,
     * otherwise the raw handle URL (which the importer resolves before use).
     */
    public data class YouTubeChannel(override val title: String, val channelUrl: HttpUrl) : ImportedSource
}

/** Outcome of parsing an import file; expected failures are values. */
public sealed interface ImportParseResult {
    public data class Success(val sources: List<ImportedSource>) : ImportParseResult
    public data class Failure(val detail: String) : ImportParseResult
}

/**
 * Reads the subscription-export formats a switcher actually arrives with:
 * OPML (AntennaPod and most podcast apps), NewPipe/PipePipe JSON, and Google
 * Takeout's `subscriptions.csv`. The format is sniffed from the content, so the
 * caller need not know which they picked. YouTube feeds inside an OPML are
 * recognised as channels, not podcasts.
 */
public class SubscriptionImportParser {

    public fun parse(content: String): ImportParseResult {
        // Drop leading whitespace AND a UTF-8 BOM (some exporters emit one), so a
        // BOM-prefixed OPML/JSON is still recognised by its first real character.
        val trimmed = content.dropWhile { it.isWhitespace() || it == BYTE_ORDER_MARK }
        val sources = runCatching {
            when (trimmed.firstOrNull()) {
                '<' -> parseOpml(content)
                '{', '[' -> parseNewPipeJson(content)
                else -> parseTakeoutCsv(content)
            }
        }.getOrElse { return ImportParseResult.Failure("Could not read this file: ${it.message}") }

        return if (sources.isEmpty()) {
            ImportParseResult.Failure("No subscriptions found in this file")
        } else {
            ImportParseResult.Success(sources)
        }
    }

    private fun parseOpml(xml: String): List<ImportedSource> {
        val root = hardenedDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            .documentElement
        return root.descendantsNamed("outline").mapNotNull { it.toImportedSource() }
    }

    private fun Element.toImportedSource(): ImportedSource? {
        val feed = (attr("xmlUrl") ?: attr("xmlurl"))?.let(HttpUrl::parse) ?: return null
        val title = attr("text") ?: attr("title") ?: feed.value
        return channelUrlIn(feed.value)
            ?.let { ImportedSource.YouTubeChannel(title, it) }
            ?: ImportedSource.Podcast(title, feed)
    }

    private fun parseNewPipeJson(json: String): List<ImportedSource> {
        val root = LENIENT_JSON.parseToJsonElement(json).jsonObject
        val subscriptions = root["subscriptions"] as? JsonArray ?: return emptyList()
        return subscriptions.mapNotNull { element ->
            val obj = element.jsonObject
            val serviceId = obj["service_id"]?.jsonPrimitive?.intOrNull
            if (serviceId != null && serviceId != YOUTUBE_SERVICE_ID) return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val channelUrl = channelUrlIn(url) ?: HttpUrl.parse(url) ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: channelUrl.value
            ImportedSource.YouTubeChannel(name, channelUrl)
        }
    }

    /** Takeout gives `Channel Id, Channel Url, Channel Title`; the header row has no channel URL, so it drops out. */
    private fun parseTakeoutCsv(csv: String): List<ImportedSource> =
        csv.lineSequence().mapNotNull { line ->
            val fields = csvFields(line)
            // The "Channel Id" column is a bare UC id, so match the URL column specifically.
            val urlIndex = fields.indexOfFirst { HttpUrl.parse(it) != null && channelUrlIn(it) != null }
            if (urlIndex < 0) return@mapNotNull null
            val channelUrl = channelUrlIn(fields[urlIndex]) ?: return@mapNotNull null
            val title = fields.getOrNull(urlIndex + 1)?.ifBlank { null } ?: channelUrl.value
            ImportedSource.YouTubeChannel(title, channelUrl)
        }.toList()

    private fun Element.attr(name: String): String? = getAttribute(name).trim().ifBlank { null }

    private companion object {
        const val YOUTUBE_SERVICE_ID = 0
        const val BYTE_ORDER_MARK = '\uFEFF'
        val LENIENT_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** A `UC…` channel id found anywhere in [raw] (bare id, channel URL, or feed URL), normalised to a channel URL. */
internal fun channelUrlIn(raw: String): HttpUrl? {
    val id = raw.findYouTubeChannelId() ?: return null
    return youTubeChannelUrl(id)
}

/** Splits one CSV line, honouring double-quoted fields and `""` escapes. */
internal fun csvFields(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && line.getOrNull(i + 1) == '"' -> {
                current.append('"')
                i++
            }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                fields += current.toString().trim()
                current.clear()
            }
            else -> current.append(c)
        }
        i++
    }
    fields += current.toString().trim()
    return fields
}
