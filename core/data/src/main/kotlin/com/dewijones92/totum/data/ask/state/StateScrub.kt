package com.dewijones92.totum.data.ask.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public object StateScrub {

    public data class Scrubbed(val element: JsonElement, val droppedKeys: Int, val redactedValues: Int)

    public fun clean(element: JsonElement): Scrubbed {
        val tally = Tally()
        val cleaned = walk(element, tally)
        return Scrubbed(cleaned, tally.keys, tally.values)
    }

    public fun redact(text: String): String = redact(text, Tally())

    public fun isSecretKey(name: String): Boolean = SECRET_KEY.containsMatchIn(name)

    public fun looksSecret(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }

    private class Tally(var keys: Int = 0, var values: Int = 0)

    private fun walk(element: JsonElement, tally: Tally): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.filterNot { (key, _) -> isSecretKey(key).also { if (it) tally.keys++ } }
                .associate { (key, value) -> key to walk(value, tally) },
        )
        is JsonArray -> JsonArray(element.map { walk(it, tally) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content, tally)) else element
    }

    private fun redact(text: String, tally: Tally): String {
        var cleaned = text
        cleaned = URL_WITH_QUERY.replace(cleaned) { match -> trimQuery(match, tally) }
        PATTERNS.forEach { pattern ->
            cleaned = pattern.replace(cleaned) {
                tally.values++
                REDACTED
            }
        }
        cleaned = KEYED_VALUE.replace(cleaned) { match ->
            tally.values++
            match.groupValues[1] + REDACTED
        }
        return cleaned
    }

    private fun trimQuery(match: MatchResult, tally: Tally): String {
        val base = match.groupValues[1]
        val kept = match.groupValues[2].split('&').filter { pair -> pair.substringBefore('=') in SAFE_PARAMS }
        val dropped = match.groupValues[2].split('&').size - kept.size
        if (dropped > 0) tally.values += dropped
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    private const val REDACTED = "[redacted]"

    private val SAFE_PARAMS = setOf("v", "list", "t", "index")

    private val URL_WITH_QUERY = Regex("""(https?://[^\s"'<>?#]+)\?([^\s"'<>#]*)""")

    private val SECRET_KEY = Regex(
        "(?i)(token|secret|passw|credential|authoriz|api[_-]?key|apikey|cookie|bearer|signature|refresh|" +
            "private[_-]?key|access[_-]?key|session[_-]?id)",
    )

    private val PATTERNS = listOf(
        Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}"""),
        Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}"""),
        Regex("""ya29\.[A-Za-z0-9._-]{16,}"""),
        Regex("""1//[A-Za-z0-9._-]{20,}"""),
        Regex("""AIza[A-Za-z0-9_-]{30,}"""),
        Regex("""sk-[A-Za-z0-9_-]{20,}"""),
        Regex("""(?<!torrent:)(?<!btih:)(?<![A-Za-z0-9])[A-Fa-f0-9]{40,}(?![A-Za-z0-9])"""),
        Regex("""://[^/\s:@]+:[^/\s@]+@"""),
    )

    private val KEYED_VALUE = Regex(
        """(?i)\b((?:[\w-]*(?:token|secret|password|passwd|api[_-]?key|authorization|cookie)[\w-]*)\s*[:=]\s*)""" +
            """(?!\[redacted])[^\s,;"'&]+""",
    )
}
