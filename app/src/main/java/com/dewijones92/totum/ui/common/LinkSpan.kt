package com.dewijones92.totum.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.dewijones92.totum.ui.player.Timestamps

sealed interface LinkSpan {
    val range: IntRange

    data class Web(override val range: IntRange, val url: String) : LinkSpan

    data class Seek(override val range: IntRange, val positionMs: Long) : LinkSpan
}

private val WEB_ADDRESS = Regex("""https?://[^\s<>"]+""")
private const val TRAILING_PUNCTUATION = ".,;:!?)]}'\""

fun linkSpans(text: String): List<LinkSpan> {
    val web = WEB_ADDRESS.findAll(text).map { match ->
        val url = match.value.trimEnd { it in TRAILING_PUNCTUATION }
        LinkSpan.Web(match.range.first until match.range.first + url.length, url)
    }.toList()
    val seeks = Timestamps.find(text)
        .filter { seek -> web.none { seek.range.first in it.range } }
        .map { LinkSpan.Seek(it.range, it.positionMs) }
    return (web + seeks).sortedBy { it.range.first }
}

fun String.withLinks(
    linkColor: Color,
    onOpen: (String) -> Unit,
    onSeekTo: ((Long) -> Unit)? = null,
): AnnotatedString {
    val spans = linkSpans(this).filter { it is LinkSpan.Web || onSeekTo != null }
    if (spans.isEmpty()) return AnnotatedString(this)
    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var cursor = 0
        spans.forEach { span ->
            append(substring(cursor, span.range.first))
            val link = when (span) {
                is LinkSpan.Web -> LinkAnnotation.Url(span.url, style) { onOpen(span.url) }
                is LinkSpan.Seek -> LinkAnnotation.Clickable(span.positionMs.toString(), style) {
                    onSeekTo?.invoke(span.positionMs)
                }
            }
            withLink(link) { append(substring(span.range)) }
            cursor = span.range.last + 1
        }
        append(substring(cursor))
    }
}
