package com.dewijones92.totum.data.audiobook

import com.dewijones92.totum.common.NaturalOrder
import com.dewijones92.totum.data.rss.ParsedEpisode
import com.dewijones92.totum.data.rss.ParsedFeed
import com.dewijones92.totum.data.rss.RssParseResult
import com.dewijones92.totum.domain.AUDIO_EXTENSIONS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public object ArchiveItemParser {

    private val json = Json { ignoreUnknownKeys = true }

    private val SPEECH_FORMATS = listOf("64Kbps MP3", "VBR MP3", "128Kbps MP3", "MP3", "Ogg Vorbis")

    private const val SECONDS_A_MINUTE = 60

    private class File(val name: String, val format: String?, val title: String?, val track: Int?, val length: String?)

    @Suppress("ReturnCount")
    public fun parse(body: String): RssParseResult {
        val item = runCatching { json.parseToJsonElement(body) as JsonObject }
            .getOrElse { return RssParseResult.Failure("Not an Internet Archive item: ${it.message}") }
        val metadata = item["metadata"] as? JsonObject
            ?: return RssParseResult.Failure("The Internet Archive has no such item")
        val identifier = metadata.archiveText("identifier")
            ?: return RssParseResult.Failure("The Internet Archive item has no identifier")
        val files = (item["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toFile() }
        val chapters = chaptersOf(files)
        if (chapters.isEmpty()) return RssParseResult.Failure("The Internet Archive item \"$identifier\" has no audio")
        val creator = metadata.archiveTexts("creator").joinToString(", ").ifBlank { null }
        return RssParseResult.Success(
            ParsedFeed(
                title = metadata.archiveText("title") ?: identifier,
                author = creator,
                description = metadata.archiveText("description"),
                websiteUrl = ArchiveBooks.detailsUrl(identifier),
                imageUrl = ArchiveBooks.artworkUrl(identifier).value,
                episodes = chapters.map { file ->
                    ParsedEpisode(
                        guid = null,
                        title = file.title ?: file.name.substringAfterLast('/').substringBeforeLast('.'),
                        author = creator,
                        enclosureUrl = ArchiveBooks.fileUrl(identifier, file.name),
                        publishedAt = null,
                        duration = file.length?.let(::lengthOf),
                        description = null,
                        imageUrl = null,
                    )
                },
            ),
        )
    }

    private fun chaptersOf(files: List<File>): List<File> {
        val chosen = SPEECH_FORMATS
            .firstNotNullOfOrNull { format -> files.filter { it.format == format }.ifEmpty { null } }
            ?: files.filter { it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
        val trackByTitle = files.filter { it.track != null && it.title != null }.associate { it.title to it.track }
        return chosen.sortedWith(
            compareBy<File, Int?>(nullsLast()) { it.track ?: trackByTitle[it.title] }
                .thenBy(NaturalOrder) { it.name },
        )
    }

    private fun lengthOf(text: String): Duration? {
        val parts = text.split(':')
        val seconds = if (parts.size == 1) {
            text.toDoubleOrNull()
        } else {
            parts.map { it.toDoubleOrNull() ?: return null }.fold(
                0.0
            ) { total, part -> total * SECONDS_A_MINUTE + part }
        }
        return seconds?.takeIf { it > 0 }?.seconds
    }

    private fun JsonObject.toFile(): File? = File(
        name = archiveText("name") ?: return null,
        format = archiveText("format"),
        title = archiveText("title"),
        track = archiveText("track")?.substringBefore('/')?.trim()?.toIntOrNull(),
        length = archiveText("length"),
    )
}
