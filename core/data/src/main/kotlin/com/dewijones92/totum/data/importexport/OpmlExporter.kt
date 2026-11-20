package com.dewijones92.totum.data.importexport

import com.dewijones92.totum.data.bbc.BbcProgrammes
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.youTubeChannelId

/**
 * Serialises the current subscriptions to OPML 2.0 — the lingua franca podcast
 * apps import. Both pillars go in: podcasts by their feed URL, YouTube channels
 * by their uploads-feed URL (`feeds/videos.xml?channel_id=…`), so the file
 * round-trips back through [SubscriptionImportParser] and is equally importable
 * by other apps.
 */
public class OpmlExporter {

    public fun export(sources: List<MediaSource>, title: String = DEFAULT_TITLE): String {
        val outlines = sources.mapNotNull(::outlineFor).joinToString("\n")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<opml version=\"2.0\">\n")
            append("  <head>\n    <title>").append(escape(title)).append("</title>\n  </head>\n")
            append("  <body>\n")
            if (outlines.isNotEmpty()) append(outlines).append("\n")
            append("  </body>\n")
            append("</opml>\n")
        }
    }

    private fun outlineFor(source: MediaSource): String? = when (source) {
        is MediaSource.PodcastFeed -> source.takeUnless { BbcProgrammes.isProgramme(it.feedUrl) }
            ?.let { outline(it.title, it.feedUrl.value, it.websiteUrl?.value) }
        is MediaSource.VideoChannel -> {
            val channelId = source.youTubeChannelId
            channelId?.let {
                outline(
                    source.title,
                    "https://www.youtube.com/feeds/videos.xml?channel_id=$it",
                    source.channelUrl.value
                )
            }
        }
    }

    private fun outline(text: String, xmlUrl: String, htmlUrl: String?): String = buildString {
        append("    <outline type=\"rss\" text=\"").append(escape(text))
        append("\" title=\"").append(escape(text))
        append("\" xmlUrl=\"").append(escape(xmlUrl)).append('"')
        if (htmlUrl != null) append(" htmlUrl=\"").append(escape(htmlUrl)).append('"')
        append("/>")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        const val DEFAULT_TITLE = "Totum subscriptions"
    }
}
