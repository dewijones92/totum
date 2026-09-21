package com.dewijones92.totum.data.rss

import com.dewijones92.totum.data.xml.childElements
import com.dewijones92.totum.data.xml.firstChildElement
import com.dewijones92.totum.data.xml.firstChildText
import com.dewijones92.totum.data.xml.hardenedDocumentBuilderFactory
import com.dewijones92.totum.domain.Chapter
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Result of parsing feed XML. */
public sealed interface RssParseResult {
    public data class Success(val feed: ParsedFeed) : RssParseResult
    public data class Failure(val detail: String) : RssParseResult
}

/** An RSS 2.0 podcast feed, as found in the wild. */
public data class ParsedFeed(
    val title: String,
    /**
     * The channel's `itunes:author` — the network or publisher behind the show, which is a
     * different fact from [title] and the one most feeds actually set. Episodes carry their own
     * (often absent), so this is the fallback every episode shares.
     */
    val author: String?,
    val description: String?,
    val websiteUrl: String?,
    val episodes: List<ParsedEpisode>,
)

public data class ParsedEpisode(
    val guid: String?,
    val title: String,
    val author: String?,
    val enclosureUrl: String?,
    val publishedAt: Instant?,
    val duration: Duration?,
    val description: String?,
    val imageUrl: String?,
    val chapters: List<Chapter> = emptyList(),
    /** Podcasting 2.0 remote chapters URL (`<podcast:chapters url=…>`); fetched if no inline chapters. */
    val chaptersUrl: String? = null,
)

/**
 * Parses RSS 2.0 podcast feeds (with the usual iTunes extensions) via the
 * platform DOM parser — available identically on the JVM and Android.
 * Tolerant by design: a feed needs a channel title to be usable; anything
 * else missing degrades to null rather than failing the feed.
 */
public class RssParser {

    public fun parse(xml: String): RssParseResult {
        val document = runCatching {
            hardenedDocumentBuilderFactory()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }.getOrElse { return RssParseResult.Failure("Not parseable XML: ${it.message}") }

        val channel = document.documentElement
            ?.takeIf { it.tagName == "rss" }
            ?.firstChildElement("channel")
            ?: return RssParseResult.Failure("Not an RSS document")

        val title = channel.firstChildText("title")
            ?: return RssParseResult.Failure("Feed has no channel title")

        val episodes = channel.childElements("item").map { it.toEpisode() }

        return RssParseResult.Success(
            ParsedFeed(
                title = title,
                author = channel.firstChildText("itunes:author") ?: channel.firstChildText("author"),
                description = channel.firstChildText("description"),
                websiteUrl = channel.firstChildText("link"),
                episodes = episodes,
            ),
        )
    }

    private fun Element.toEpisode(): ParsedEpisode = ParsedEpisode(
        guid = firstChildText("guid"),
        title = firstChildText("title") ?: UNTITLED_EPISODE,
        author = firstChildText("itunes:author") ?: firstChildText("author"),
        enclosureUrl = firstChildElement("enclosure")?.getAttribute("url")?.ifBlank { null },
        publishedAt = firstChildText("pubDate")?.let(::parseRfc1123OrNull),
        duration = firstChildText("itunes:duration")?.let(::parseItunesDurationOrNull),
        description = firstChildText("description"),
        imageUrl = firstChildElement("itunes:image")?.getAttribute("href")?.ifBlank { null },
        chapters = parseChapters(),
        chaptersUrl = firstChildElement("podcast:chapters")?.getAttribute("url")?.ifBlank { null },
    )

    /** Inline Podlove Simple Chapters: `<psc:chapters><psc:chapter start=".." title=".."/>…`. */
    private fun Element.parseChapters(): List<Chapter> {
        val container = firstChildElement("psc:chapters") ?: return emptyList()
        return container.childElements("psc:chapter").mapNotNull { chapter ->
            val start = parseNptOrNull(chapter.getAttribute("start")) ?: return@mapNotNull null
            val title = chapter.getAttribute("title").trim().ifBlank { null } ?: return@mapNotNull null
            Chapter(start, title)
        }
    }

    /** Normal Play Time ("HH:MM:SS(.mmm)" / "MM:SS" / seconds); allows 0, since chapters start at 0. */
    private fun parseNptOrNull(text: String): Duration? =
        splitClockToSeconds(text)?.takeIf { it >= 0 && it.isFinite() }?.seconds

    /** iTunes duration comes as "HH:MM:SS", "MM:SS", or plain seconds; must be positive. */
    private fun parseItunesDurationOrNull(text: String): Duration? =
        splitClockToSeconds(text)?.takeIf { it > 0 && it.isFinite() }?.seconds

    /**
     * Colon-separated clock ("H:MM:SS(.mmm)" / "M:SS" / bare seconds) to total
     * seconds; null if any part isn't a number or there are more than three.
     * Shared by the chapter (NPT) and iTunes-duration parsers, which differ only
     * in their accept policy (zero-allowed vs positive-only).
     */
    private fun splitClockToSeconds(text: String): Double? {
        val parts = text.trim().ifBlank { return null }.split(":")
        if (parts.size > HOURS_MINUTES_SECONDS_PARTS) return null
        val nums = parts.map { it.toDoubleOrNull() ?: return null }
        return when (nums.size) {
            MINUTES_SECONDS_PARTS -> nums[0] * SECONDS_PER_MINUTE + nums[1]
            HOURS_MINUTES_SECONDS_PARTS -> (nums[0] * MINUTES_PER_HOUR + nums[1]) * SECONDS_PER_MINUTE + nums[2]
            else -> nums[0]
        }
    }

    private fun parseRfc1123OrNull(text: String): Instant? = runCatching {
        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(text.trim()))
    }.getOrNull()

    private companion object {
        const val UNTITLED_EPISODE = "Untitled episode"
        const val SECONDS_PER_MINUTE = 60L
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_SECONDS_PARTS = 2
        const val HOURS_MINUTES_SECONDS_PARTS = 3
    }
}
